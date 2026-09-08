package com.trackngo.booking.internal.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Processes disruption refunds after the cancellation transaction commits.
 * Stripe idempotency keys make retries safe if multiple app instances run it.
 */
@Service
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    private final JdbcTemplate jdbc;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    /** Consecutive polls that found no pending refund. Drives the idle backoff. */
    @Value("${trackngo.refunds.idle-after-empty-polls:5}")
    private int idleAfterEmptyPolls;

    /** Once idle, run the query only every Nth tick instead of every tick. */
    @Value("${trackngo.refunds.idle-backoff-multiplier:15}")
    private volatile int idleBackoffMultiplier;

    /*
      Only ever read and written by the scheduler, and Spring never runs a
      fixedDelay method concurrently with itself, so these need no locking. They
      are volatile because successive runs may land on different threads in the
      scheduling pool.
    */
    private volatile int consecutiveEmptyPolls = 0;
    private volatile int ticksSinceLastQuery = 0;

    public RefundProcessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks for refunds waiting to be sent to Stripe.
     *
     * <p>The tick stays fast so that a refund raised by a disruption is picked up
     * within a second, as before. What changed is what happens when there is
     * nothing to do, which is almost always: after a few empty polls in a row the
     * query is only run every Nth tick, so an idle system stops issuing a
     * three-table join against the database every single second - roughly 86,000
     * of them a day, essentially all returning nothing. The moment a refund does
     * appear, the backoff resets and the poller is back to checking every tick.
     */
    @Scheduled(fixedDelayString = "${trackngo.refunds.poll-ms:1000}")
    public void processPendingStripeRefunds() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return;
        }
        if (!dueForQuery()) {
            return;
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.refund_id,
                       r.refund_amount,
                       r.disruption_key,
                       sb.passenger_id,
                       p.payment_id,
                       p.provider_transaction_id
                FROM refund r
                JOIN payment p ON p.payment_id = r.payment_id
                JOIN seat_booking sb ON sb.payment_id = p.payment_id
                WHERE r.refund_status = 'pending'
                  AND p.payment_method = 'stripe'
                  AND p.provider_transaction_id IS NOT NULL
                  AND p.provider_transaction_id <> ''
                ORDER BY r.refund_id
                LIMIT 25
                """);

        if (rows.isEmpty()) {
            // Saturating, so a system that is idle for months cannot overflow it.
            if (consecutiveEmptyPolls < Integer.MAX_VALUE) {
                consecutiveEmptyPolls++;
            }
            return;
        }

        // Work found: drop straight back to checking on every tick.
        consecutiveEmptyPolls = 0;
        log.info("Processing {} pending Stripe disruption refund(s)", rows.size());

        Stripe.apiKey = stripeSecretKey;
        for (Map<String, Object> row : rows) {
            processOne(row);
        }
    }

    /*
      True when this tick should actually query the database.

      While refunds are flowing (fewer than idleAfterEmptyPolls empty results in a
      row) every tick queries, exactly as before. Once the system has clearly gone
      quiet, only one tick in idleBackoffMultiplier does - so the worst case for
      noticing a refund raised during a quiet spell is poll-ms multiplied by the
      backoff, and the refund itself still reaches Stripe in the same call.
    */
    private boolean dueForQuery() {
        if (consecutiveEmptyPolls < idleAfterEmptyPolls) {
            ticksSinceLastQuery = 0;
            return true;
        }
        int multiplier = Math.max(1, idleBackoffMultiplier);
        if (++ticksSinceLastQuery >= multiplier) {
            ticksSinceLastQuery = 0;
            return true;
        }
        return false;
    }

    private void processOne(Map<String, Object> row) {
        Long refundId = number(row.get("refund_id"));
        Long paymentId = number(row.get("payment_id"));
        Long passengerId = number(row.get("passenger_id"));
        String providerTransactionId = (String) row.get("provider_transaction_id");
        String disruptionKey = (String) row.get("disruption_key");
        BigDecimal amount = (BigDecimal) row.get("refund_amount");

        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(providerTransactionId)
                    .setAmount(amount.movePointRight(2).longValueExact())
                    .putMetadata("disruption_key", disruptionKey)
                    .build();
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(disruptionKey)
                    .build();
            Refund refund = Refund.create(params, options);

            int processed = jdbc.update("""
                    UPDATE refund
                    SET refund_status = 'processed',
                        provider_refund_id = ?,
                        processed_date = CURRENT_TIMESTAMP,
                        attempt_count = attempt_count + 1,
                        last_error = NULL
                    WHERE refund_id = ? AND refund_status = 'pending'
                    """, refund.getId(), refundId);
            if (processed == 1) {
                jdbc.update("UPDATE payment SET payment_status = 'refunded' WHERE payment_id = ?", paymentId);
                jdbc.update("""
                        INSERT INTO notification
                            (notification_type, title, message, passenger_id)
                        VALUES ('payment', ?, ?, ?)
                        """,
                        "Refund processed",
                        "Your disruption refund of LKR " + amount + " has been processed successfully.",
                        passengerId
                );
            }
        } catch (StripeException | ArithmeticException ex) {
            jdbc.update("""
                    UPDATE refund
                    SET attempt_count = attempt_count + 1,
                        last_error = ?
                    WHERE refund_id = ? AND refund_status = 'pending'
                    """, ex.getMessage(), refundId);
            log.warn("Refund {} failed and will be retried: {}", refundId, ex.getMessage());
        } catch (RuntimeException ex) {
            jdbc.update("""
                    UPDATE refund
                    SET attempt_count = attempt_count + 1,
                        last_error = ?
                    WHERE refund_id = ? AND refund_status = 'pending'
                    """, ex.getMessage(), refundId);
            log.error("Unexpected refund-processing failure for refund {}; it will be retried", refundId, ex);
        }
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
