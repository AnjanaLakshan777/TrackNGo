package com.trackngo.tracking.internal.service;

import com.trackngo.tracking.api.dto.LiveBusLocationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes accepted GPS fixes into {@code bus_locations}.
 *
 * <p>Live tracking keeps the newest fix per bus in memory and pushes it to
 * passengers over the WebSocket, which is all the map needs. Nothing outside
 * that process could see it, so the AI assistant - which reads the table - was
 * answering "where is this bus" from whatever happened to be in the table,
 * which on a fresh install is seed data weeks old. Recording the fix here is
 * what connects the two.
 *
 * <p>One row is kept per bus rather than a full trail: the only reader wants the
 * latest position, and an append per ping would grow without bound.
 *
 * <p>A failure to record must never cost the passenger their live map, so every
 * problem is swallowed after logging and the caller carries on broadcasting.
 *
 * <h2>Why this does not write every fix</h2>
 *
 * <p>Driver phones report a position every couple of seconds. Writing each one
 * meant a database round trip per bus per two seconds - with a fleet of fifty
 * that is roughly twenty-five writes a second, every one of them a network round
 * trip to a remote database, and all of them on the request thread that the
 * driver's app is waiting on.
 *
 * <p>Two changes fix that without changing what anyone sees:
 *
 * <ul>
 *   <li>The write runs on a background thread ({@code @Async}), so the driver's
 *       POST returns as soon as the fix has been accepted and broadcast. The
 *       passenger's map is fed from memory and never waited on this write.</li>
 *   <li>Consecutive writes for the same bus are spaced at least
 *       {@code trackngo.tracking.location-write-min-interval-ms} apart. The only
 *       reader of the row is the AI assistant answering a question a person just
 *       typed; it does not need two-second freshness, and the live map - which
 *       does - is not served from here. The first fix for a bus is always
 *       written immediately, so a bus that has just started reporting appears
 *       straight away.</li>
 * </ul>
 *
 * <p>Setting the interval to 0 restores a write per accepted fix.
 */
@Service
@Slf4j
public class BusLocationRecorder {

    private final JdbcTemplate jdbc;

    /** Minimum gap between persisted writes for one bus; 0 disables throttling. */
    private final long minWriteIntervalMs;

    /**
     * When each bus's position was last written. Bounded by the size of the
     * fleet, and keyed exactly like the in-memory fix map that
     * {@link LiveLocationQualityService} already keeps.
     */
    private final Map<String, Long> lastWrittenAt = new ConcurrentHashMap<>();

    public BusLocationRecorder(
            JdbcTemplate jdbc,
            @Value("${trackngo.tracking.location-write-min-interval-ms:15000}") long minWriteIntervalMs) {
        this.jdbc = jdbc;
        this.minWriteIntervalMs = minWriteIntervalMs;
    }

    @Async
    public void record(LiveBusLocationDto fix) {
        if (fix == null || fix.getBusNumber() == null || fix.getBusNumber().isBlank()) {
            return;
        }
        if (fix.getLatitude() == null || fix.getLongitude() == null) {
            return;
        }
        if (!claimWriteSlot(fix.getBusNumber(), System.currentTimeMillis())) {
            return;
        }

        // The moment the server accepted the fix, not the device's own clock,
        // which may be wrong and would then make the row look stale or future-dated.
        LocalDateTime recordedAt = fix.getServerTimestamp() == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(fix.getServerTimestamp()), ZoneId.systemDefault());

        try {
            int updated = jdbc.update("""
                    UPDATE bus_locations
                    SET latitude = ?, longitude = ?, heading = ?, speed = ?, recorded_at = ?
                    WHERE bus_number = ?
                    """,
                    fix.getLatitude(),
                    fix.getLongitude(),
                    fix.getHeading(),
                    fix.getSpeed(),
                    Timestamp.valueOf(recordedAt),
                    fix.getBusNumber());

            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO bus_locations
                            (name, bus_number, latitude, longitude, heading, speed, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        fix.getBusNumber(),
                        fix.getBusNumber(),
                        fix.getLatitude(),
                        fix.getLongitude(),
                        fix.getHeading(),
                        fix.getSpeed(),
                        Timestamp.valueOf(recordedAt));
            }
        } catch (Exception ex) {
            log.warn("Could not record location for {}: {}", fix.getBusNumber(), ex.getMessage());
        }
    }

    /*
      Decides whether this fix is the one that gets written, and claims the slot
      atomically so two fixes arriving for the same bus at once cannot both pass.
      A bus that has never been written is always allowed through.
    */
    private boolean claimWriteSlot(String busNumber, long now) {
        if (minWriteIntervalMs <= 0) {
            return true;
        }
        Long claimed = lastWrittenAt.compute(busNumber, (key, previous) ->
                (previous == null || now - previous >= minWriteIntervalMs) ? now : previous);
        return claimed != null && claimed == now;
    }
}
