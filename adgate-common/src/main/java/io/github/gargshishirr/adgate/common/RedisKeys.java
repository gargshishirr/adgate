package io.github.gargshishirr.adgate.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The Redis key layout shared by the spend aggregator (writer) and the eligibility service (reader).
 * Keeping it in one place is what makes the two services agree on where state lives.
 *
 * <p>Every id that goes into a key must be a {@linkplain #isSafeId safe id}: no ':' (the separator), no glob
 * characters and no whitespace. Each kind of key also has its own second segment, so a key built from user input
 * can never land in another key's namespace.
 */
public final class RedisKeys {

    /** Longest id accepted in a key. */
    public static final int MAX_ID_LENGTH = 128;

    /** Regex form of {@link #isSafeId}, for bean validation: 1 to 128 of [A-Za-z0-9._-], starting with a letter or digit. */
    public static final String SAFE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private static final String PREFIX = "adgate:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** Set of all campaign ids, used by admin listing. */
    public static final String CAMPAIGN_INDEX = PREFIX + "campaigns";

    private RedisKeys() {
    }

    /** True if the id may be used in a key: 1 to 128 letters, digits, '.', '_' or '-', starting with a letter or digit. */
    public static boolean isSafeId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphanumeric && (i == 0 || (c != '.' && c != '_' && c != '-'))) {
                return false;
            }
        }
        return true;
    }

    /** Micros spent by a campaign during one UTC day. */
    public static String spend(String campaignId, LocalDate utcDay) {
        return PREFIX + "spend:" + requireSafeId(campaignId, "campaign id") + ":" + DAY.format(utcDay);
    }

    /** Marker that a spend event was already applied; makes the ledger idempotent. */
    public static String spendDedupe(String eventId) {
        return PREFIX + "spend-dedupe:" + requireSafeId(eventId, "event id");
    }

    /**
     * Low watermark for one partition of the spend topic: every spend event appended to that partition
     * at or before this epoch-millis value has been applied to the ledger.
     */
    public static String watermark(int partition) {
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        return PREFIX + "spend-wm:" + partition;
    }

    /** JSON configuration of one campaign. */
    public static String campaign(String campaignId) {
        return PREFIX + "campaign:" + requireSafeId(campaignId, "campaign id");
    }

    /** Request counter for one device in one fixed traffic-quality window. */
    public static String deviceRate(String deviceId, long windowStartEpochSeconds) {
        return PREFIX + "rate:" + requireSafeId(deviceId, "device id") + ":" + windowStartEpochSeconds;
    }

    public static LocalDate utcDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** The message names the kind of id but never echoes it, so callers can log it safely. */
    private static String requireSafeId(String id, String kind) {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("Not a safe " + kind + " for a Redis key");
        }
        return id;
    }
}
