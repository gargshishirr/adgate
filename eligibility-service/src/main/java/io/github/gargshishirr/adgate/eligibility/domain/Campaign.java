package io.github.gargshishirr.adgate.eligibility.domain;

import java.util.List;
import java.util.Set;

/**
 * Serving configuration of a campaign.
 *
 * @param placements       placements the campaign may serve on; empty means all
 * @param negativeKeywords phrases that must not appear in the search query
 */
public record Campaign(
        String id,
        CampaignStatus status,
        long dailyBudgetMicros,
        PacingMode pacing,
        Set<Placement> placements,
        List<String> negativeKeywords) {

    public Campaign {
        placements = placements == null ? Set.of() : Set.copyOf(placements);
        negativeKeywords = negativeKeywords == null ? List.of() : List.copyOf(negativeKeywords);
    }
}
