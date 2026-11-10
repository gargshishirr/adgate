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

    public boolean isValid() {
        return eventId != null && !eventId.isBlank()
                && campaignId != null && !campaignId.isBlank()
                && costMicros > 0
                && eventTimeMillis > 0;
    }
}
