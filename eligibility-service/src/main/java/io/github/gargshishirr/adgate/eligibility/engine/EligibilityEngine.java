package io.github.gargshishirr.adgate.eligibility.engine;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.CampaignStatus;
import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Candidate;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.domain.ReasonCode;

/**
 * Decides, for every candidate of an ad request, whether it may serve.
 *
 * <p>The engine is a pure function of the request and a {@link ServingState} snapshot: it does no I/O and never
 * reads the clock. That makes every decision unit-testable and exactly replayable from a stored snapshot.
 *
 * <p>Checks run cheapest and broadest first, and the first failing check is the reason that is reported. Any
 * missing or stale input leads to a decline with a fail-closed reason code, never to an optimistic serve.
 */
public final class EligibilityEngine {

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    private final EngineSettings settings;

    public EligibilityEngine(EngineSettings settings) {
        this.settings = settings;
    }

    public List<Decision> evaluate(EligibilityRequest request, ServingState state) {
        List<Candidate> candidates = request.candidates();

        switch (state.dependencyStatus()) {
            case OK -> {
            }
            case DEADLINE_EXCEEDED -> {
                return declineAll(candidates, ReasonCode.DEADLINE_EXCEEDED, state.dependencyDetail());
            }
            default -> {
                return declineAll(candidates, ReasonCode.DEPENDENCY_UNAVAILABLE,
                        state.dependencyStatus() + ": " + state.dependencyDetail());
            }
        }

        String blockedToken = blockedUserAgentToken(request.device().userAgent());
        if (blockedToken != null) {
            return declineAll(candidates, ReasonCode.TRAFFIC_INVALID_USER_AGENT, "user agent contains '" + blockedToken + "'");
        }
        if (state.deviceRequestsInWindow() > settings.deviceRequestLimit()) {
            return declineAll(candidates, ReasonCode.TRAFFIC_RATE_EXCEEDED, String.format(Locale.ROOT,
                    "device made %d requests in a %ds window (limit %d)", state.deviceRequestsInWindow(),
                    settings.deviceRateWindow().toSeconds(), settings.deviceRequestLimit()));
        }

        List<String> queryTokens = QueryNormalizer.tokens(request.query());
        List<Decision> decisions = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            decisions.add(evaluateCandidate(request, state, candidate, queryTokens));
        }
        return List.copyOf(decisions);
    }

    private Decision evaluateCandidate(EligibilityRequest request, ServingState state, Candidate candidate, List<String> queryTokens) {
        CampaignState campaignState = state.campaigns().get(candidate.campaignId());
        if (campaignState == null || campaignState.configStatus() == ConfigStatus.UNAVAILABLE) {
            return decline(candidate, ReasonCode.DEPENDENCY_UNAVAILABLE, "campaign configuration could not be loaded");
        }
        if (campaignState.configStatus() == ConfigStatus.NOT_FOUND) {
            return decline(candidate, ReasonCode.CAMPAIGN_NOT_FOUND, "no configuration for campaign " + candidate.campaignId());
        }
        long configAgeMillis = state.nowMillis() - campaignState.configLoadedAtMillis();
        if (configAgeMillis > settings.maxConfigAge().toMillis()) {
            return decline(candidate, ReasonCode.CONFIG_STALE, String.format(Locale.ROOT,
                    "configuration is %d ms old (limit %d ms)", configAgeMillis, settings.maxConfigAge().toMillis()));
        }

        Campaign campaign = campaignState.campaign();
        if (campaign.status() == CampaignStatus.PAUSED) {
            return decline(candidate, ReasonCode.CAMPAIGN_PAUSED, "campaign is paused");
        }
        if (!campaign.placements().isEmpty() && !campaign.placements().contains(request.placement())) {
            return decline(candidate, ReasonCode.POLICY_PLACEMENT_NOT_ALLOWED,
                    "campaign does not serve on " + request.placement());
        }
        for (String keyword : campaign.negativeKeywords()) {
            if (QueryNormalizer.containsPhrase(queryTokens, QueryNormalizer.tokens(keyword))) {
                return decline(candidate, ReasonCode.POLICY_NEGATIVE_KEYWORD, "query matches negative keyword '" + keyword + "'");
            }
        }

        Long watermark = campaignState.spendWatermarkMillis();
        if (watermark == null) {
            return decline(candidate, ReasonCode.SPEND_DATA_STALE,
                    "no spend watermark yet for partition " + campaignState.spendPartition());
        }
        long spendLagMillis = state.nowMillis() - watermark;
        if (spendLagMillis > settings.maxSpendStaleness().toMillis()) {
            return decline(candidate, ReasonCode.SPEND_DATA_STALE, String.format(Locale.ROOT,
                    "spend for partition %d is %d ms behind (limit %d ms)", campaignState.spendPartition(), spendLagMillis,
                    settings.maxSpendStaleness().toMillis()));
        }

        long budget = campaign.dailyBudgetMicros();
        long spent = campaignState.spentTodayMicros();
        long exhaustedAt = budget - (long) Math.ceil(budget * settings.budgetSafetyMargin());
        if (spent >= exhaustedAt) {
            return decline(candidate, ReasonCode.BUDGET_EXHAUSTED, String.format(Locale.ROOT,
                    "spent %s of a %s daily budget", money(spent), money(budget)));
        }

        if (campaign.pacing() == PacingMode.EVEN) {
            Decision throttled = evenPacing(request, state, candidate, budget, spent);
            if (throttled != null) {
                return throttled;
            }
        }
        return Decision.eligible(candidate.adId(), candidate.campaignId(), String.format(Locale.ROOT,
                "spent %.1f%% of daily budget", percent(spent, budget)));
    }

    /** Returns a throttle decision, or null if the campaign is within its even-pacing schedule. */
    private Decision evenPacing(EligibilityRequest request, ServingState state, Candidate candidate, long budget, long spent) {
        double dayFraction = PacingPolicy.dayFraction(state.nowMillis());
        long allowance = PacingPolicy.evenPacingAllowanceMicros(budget, dayFraction, settings.pacingBurst(), settings.pacingEarlyDayAllowance());
        long headroom = allowance - spent;
        String schedule = String.format(Locale.ROOT, "spent %.1f%% of budget; even pacing allows %.1f%% by %s",
                percent(spent, budget), percent(allowance, budget), HOUR_MINUTE.format(Instant.ofEpochMilli(state.nowMillis())));
        if (headroom <= 0) {
            return decline(candidate, ReasonCode.PACING_THROTTLED, schedule);
        }
        // Near the pacing line, thin out traffic gradually instead of switching delivery off and on.
        long ramp = (long) (budget * settings.pacingRampFraction());
        if (headroom < ramp) {
            double serveProbability = (double) headroom / ramp;
            if (DeterministicSampler.unit(request.requestId(), candidate.campaignId()) >= serveProbability) {
                return decline(candidate, ReasonCode.PACING_THROTTLED, String.format(Locale.ROOT,
                        "%s; near the pacing line, serving %.0f%% of requests", schedule, serveProbability * 100));
            }
        }
        return null;
    }

    private String blockedUserAgentToken(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        for (String token : settings.blockedUserAgentTokens()) {
            if (normalized.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        return null;
    }

    private static List<Decision> declineAll(List<Candidate> candidates, ReasonCode reason, String detail) {
        return candidates.stream().map(candidate -> decline(candidate, reason, detail)).toList();
    }

    private static Decision decline(Candidate candidate, ReasonCode reason, String detail) {
        return Decision.declined(candidate.adId(), candidate.campaignId(), reason, detail);
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    private static String money(long micros) {
        return String.format(Locale.ROOT, "%.2f", micros / 1_000_000.0);
    }
}
