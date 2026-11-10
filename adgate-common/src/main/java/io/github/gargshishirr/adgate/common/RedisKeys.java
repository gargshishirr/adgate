package io.github.gargshishirr.adgate.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The Redis key layout shared by the spend aggregator (writer) and the eligibility service (reader).
 * Keeping it in one place is what makes the two services agree on where state lives.
 */
public final class RedisKeys {

    private static final String PREFIX = "adgate:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** Set of all campaign ids, used by admin listing. */
    public static final String CAMPAIGN_INDEX = PREFIX + "campaigns";

    private RedisKeys() {
    }

    /** Micros spent by a campaign during one UTC day. */
    public static String spend(String campaignId, LocalDate utcDay) {
        return PREFIX + "spend:" + campaignId + ":" + DAY.format(utcDay);
    }

    /** Marker that a spend event was already applied; makes the ledger idempotent. */
    public static String spendDedupe(String eventId) {
        return PREFIX + "spend:dedupe:" + eventId;
    }

    /**
     * Low watermark for one partition of the spend topic: every spend event appended to that partition
     * at or before this epoch-millis value has been applied to the ledger.
     */
    public static String watermark(int partition) {
        return PREFIX + "spend:wm:" + partition;
    }

    /** JSON configuration of one campaign. */
    public static String campaign(String campaignId) {
        return PREFIX + "campaign:" + campaignId;
    }

    /** Request counter for one device in one fixed traffic-quality window. */
    public static String deviceRate(String deviceId, long windowStartEpochSeconds) {
        return PREFIX + "rate:" + deviceId + ":" + windowStartEpochSeconds;
    }

    public static LocalDate utcDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
