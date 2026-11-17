package io.github.gargshishirr.adgate.common;

/**
 * A billable ad event (impression or click charge) published by the ad server once an auction is won.
 *
 * @param eventId         globally unique id; the ledger uses it to apply each charge exactly once
 * @param campaignId      campaign being charged; also the Kafka record key, so a campaign's charges stay ordered
 * @param costMicros      charge in micro-units of currency (1 USD = 1,000,000 micros) to avoid floating-point money
 * @param eventTimeMillis when the charge happened; decides which daily budget bucket it counts against
 */
public record SpendEvent(String eventId, String campaignId, long costMicros, long eventTimeMillis) {

    /** Largest single charge accepted (100,000 currency units), so daily counters can never overflow. */
    public static final long MAX_COST_MICROS = 100_000_000_000L;

    /** 10000-01-01T00:00Z. Later times cannot be bucketed into a yyyyMMdd daily key. */
    public static final long MAX_EVENT_TIME_MILLIS = 253_402_300_800_000L;

    /**
     * Invalid events are skipped and counted by the aggregator. Anything that could fail later while the batch is
     * written to Redis (an unsafe id, an overflowing cost, an unrepresentable day) must be rejected here, or one bad
     * record would block its partition forever.
     */
    public boolean isValid() {
        return RedisKeys.isSafeId(eventId)
                && RedisKeys.isSafeId(campaignId)
                && costMicros > 0 && costMicros <= MAX_COST_MICROS
                && eventTimeMillis > 0 && eventTimeMillis < MAX_EVENT_TIME_MILLIS;
    }
}
