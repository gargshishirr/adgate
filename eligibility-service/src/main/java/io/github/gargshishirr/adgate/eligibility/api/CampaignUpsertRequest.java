package io.github.gargshishirr.adgate.eligibility.api;

import java.util.List;
import java.util.Set;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.CampaignStatus;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.domain.Placement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** @param dailyBudgetMicros at most 10,000,000 currency units a day, so budget math can never overflow */
public record CampaignUpsertRequest(
        @NotNull CampaignStatus status,
        @Positive @Max(MAX_DAILY_BUDGET_MICROS) long dailyBudgetMicros,
        @NotNull PacingMode pacing,
        @Size(max = 10) Set<@NotNull Placement> placements,
        @Size(max = 200) List<@NotBlank @Size(max = 80) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                message = "must not contain control characters") String> negativeKeywords) {

    static final long MAX_DAILY_BUDGET_MICROS = 10_000_000_000_000L;

    private static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}]*";

    Campaign toCampaign(String campaignId) {
        return new Campaign(campaignId, status, dailyBudgetMicros, pacing, placements, negativeKeywords);
    }
}
