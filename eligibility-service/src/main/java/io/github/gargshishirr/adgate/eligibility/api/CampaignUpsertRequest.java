package io.github.gargshishirr.adgate.eligibility.api;

import java.util.List;
import java.util.Set;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.CampaignStatus;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.domain.Placement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CampaignUpsertRequest(
        @NotNull CampaignStatus status,
        @Positive long dailyBudgetMicros,
        @NotNull PacingMode pacing,
        Set<@NotNull Placement> placements,
        @Size(max = 200) List<@NotBlank @Size(max = 80) String> negativeKeywords) {

    Campaign toCampaign(String campaignId) {
        return new Campaign(campaignId, status, dailyBudgetMicros, pacing, placements, negativeKeywords);
    }
}
