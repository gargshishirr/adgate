package io.github.gargshishirr.adgate.eligibility.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;

/**
 * Admin view of a campaign: configuration plus live delivery state.
 *
 * @param pacingAllowancePercent share of budget even pacing allows by now (null for ASAP campaigns)
 * @param spendLagMillis         how far behind real time the campaign's spend is (null if no watermark yet)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignView(
        Campaign campaign,
        long spentTodayMicros,
        double spentPercent,
        Double pacingAllowancePercent,
        int spendPartition,
        Long spendLagMillis) {
}
