package io.github.gargshishirr.adgate.eligibility.engine;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;

/**
 * Everything the engine knows about one campaign at decision time.
 *
 * @param campaign             configuration, present only when {@code configStatus} is FOUND
 * @param configLoadedAtMillis when the configuration was last loaded from the source of truth
 * @param spentTodayMicros     spend applied to the ledger for the current UTC day
 * @param spendWatermarkMillis low watermark of the partition that carries this campaign's spend, or null if unknown
 * @param spendPartition       partition of the spend topic that owns this campaign
 */
public record CampaignState(
        String campaignId,
        ConfigStatus configStatus,
        Campaign campaign,
        long configLoadedAtMillis,
        long spentTodayMicros,
        Long spendWatermarkMillis,
        int spendPartition) {
}
