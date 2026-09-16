package com.trackngo.commons.constants;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The timezone the product operates in.
 *
 * Instants are stored in UTC - the server, MySQL and the JDBC connection all
 * agree on that, and it is the right storage model. But a calendar date is not
 * an instant: "today's manifest", "this week's earnings" and "has this licence
 * expired" all mean today *in Sri Lanka*. Asking a UTC server for LocalDate.now()
 * answers with yesterday between midnight and 05:30 Colombo, so those decisions
 * were wrong for five and a half hours out of every day.
 *
 * Asia/Colombo was already hard-coded in five separate places before this class
 * existed. This is the one definition they should all share.
 */
public final class AppZone {
    private AppZone() {}

    /** Sri Lanka. Not a fixed +05:30 offset, so any future DST rule is handled. */
    public static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    /**
     * Today's calendar date in Sri Lanka.
     *
     * Prefer this to LocalDate.now() anywhere the result is compared against a
     * DATE column - journey_date, licence_expiry, joined_date - because those
     * hold local calendar days, not instants.
     */
    public static LocalDate today() {
        return LocalDate.now(COLOMBO);
    }
}
