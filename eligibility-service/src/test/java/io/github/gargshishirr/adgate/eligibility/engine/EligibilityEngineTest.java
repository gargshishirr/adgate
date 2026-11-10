package io.github.gargshishirr.adgate.eligibility.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.CampaignStatus;
import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Candidate;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Device;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.domain.Placement;
import io.github.gargshishirr.adgate.eligibility.domain.ReasonCode;

class EligibilityEngineTest {

    /** At noon UTC even pacing allows 0.5 * 1.10 + 0.02 = 57% of the daily budget. */
    private static final long NOON = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();
    private static final long BUDGET = 100_000_000; // 100.00
    private static final String APP_STORE_UA = "AppStore/3.0 iOS/19.1";

    private final EligibilityEngine engine = new EligibilityEngine(EngineSettings.defaults());

    @Test
    void eligibleWhenEveryCheckPasses() {
        Decision decision = single(request("photo editor"), state(fresh(even("c-1"), 10_000_000)));

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.reason()).isEqualTo(ReasonCode.ELIGIBLE);
    }

    @Test
    void failsClosedForEveryCandidateWhenServingStateCannotBeRead() {
        EligibilityRequest request = request("photo editor", "c-1", "c-2");
        for (DependencyStatus status : List.of(DependencyStatus.UNAVAILABLE, DependencyStatus.CIRCUIT_OPEN)) {
            ServingState unavailable = state(fresh(even("c-1"), 0), fresh(even("c-2"), 0)).withDependencyStatus(status, "boom");

            assertThat(engine.evaluate(request, unavailable))
                    .extracting(Decision::reason)
                    .containsOnly(ReasonCode.DEPENDENCY_UNAVAILABLE);
        }
        assertThat(ReasonCode.DEPENDENCY_UNAVAILABLE.failClosed()).isTrue();
    }

    @Test
    void declinesWhenTheLatencyBudgetIsSpent() {
        ServingState late = state(fresh(even("c-1"), 0)).withDependencyStatus(DependencyStatus.DEADLINE_EXCEEDED, "took 80 ms");

        assertThat(single(request("photo editor"), late).reason()).isEqualTo(ReasonCode.DEADLINE_EXCEEDED);
    }

    @Test
    void automatedUserAgentsAreInvalidTraffic() {
        EligibilityRequest request = new EligibilityRequest("r-1", Placement.SEARCH_RESULTS, "photo editor",
                new Device("d-1", "Mozilla/5.0 (compatible; ExampleBot/2.1)"), List.of(new Candidate("ad-c-1", "c-1")));

        assertThat(single(request, state(fresh(even("c-1"), 0))).reason()).isEqualTo(ReasonCode.TRAFFIC_INVALID_USER_AGENT);
    }

    @Test
    void devicesOverTheRateLimitAreInvalidTraffic() {
        EligibilityRequest request = request("photo editor");

        assertThat(single(request, stateWithDeviceRequests(20, fresh(even("c-1"), 0))).reason()).isEqualTo(ReasonCode.ELIGIBLE);
        assertThat(single(request, stateWithDeviceRequests(21, fresh(even("c-1"), 0))).reason()).isEqualTo(ReasonCode.TRAFFIC_RATE_EXCEEDED);
    }

    @Test
    void campaignConfigurationChecks() {
        Campaign campaign = even("c-1");

        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.NOT_FOUND, null, NOON, 0, NOON, 0))).reason())
                .isEqualTo(ReasonCode.CAMPAIGN_NOT_FOUND);
        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.UNAVAILABLE, null, NOON, 0, NOON, 0))).reason())
                .isEqualTo(ReasonCode.DEPENDENCY_UNAVAILABLE);
        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.FOUND, campaign, NOON - 31_000, 0, NOON, 0))).reason())
                .isEqualTo(ReasonCode.CONFIG_STALE);
    }

    @Test
    void pausedCampaignsDoNotServe() {
        Campaign paused = new Campaign("c-1", CampaignStatus.PAUSED, BUDGET, PacingMode.EVEN, Set.of(), List.of());

        assertThat(single(request("q"), state(fresh(paused, 0))).reason()).isEqualTo(ReasonCode.CAMPAIGN_PAUSED);
    }

    @Test
    void placementPolicy() {
        Campaign todayTabOnly = new Campaign("c-1", CampaignStatus.ACTIVE, BUDGET, PacingMode.ASAP, Set.of(Placement.TODAY_TAB), List.of());

        assertThat(single(request("q"), state(fresh(todayTabOnly, 0))).reason()).isEqualTo(ReasonCode.POLICY_PLACEMENT_NOT_ALLOWED);
    }

    @Test
    void negativeKeywordsMatchNormalizedPhrasesOnTokenBoundaries() {
        CampaignState campaign = fresh(even("c-1"), 0); // negative keywords: "free", "cheap game"

        assertThat(single(request("FREE photo editor"), state(campaign)).reason()).isEqualTo(ReasonCode.POLICY_NEGATIVE_KEYWORD);
        assertThat(single(request("free-to-play"), state(campaign)).reason()).isEqualTo(ReasonCode.POLICY_NEGATIVE_KEYWORD);
        assertThat(single(request("Cheap GAMES!!"), state(campaign)).reason()).isEqualTo(ReasonCode.POLICY_NEGATIVE_KEYWORD);
        assertThat(single(request("freestyle"), state(campaign)).reason()).isEqualTo(ReasonCode.ELIGIBLE);
        assertThat(single(request("cheap fun game"), state(campaign)).reason()).isEqualTo(ReasonCode.ELIGIBLE);
    }

    @Test
    void failsClosedWhenSpendIsMissingOrStale() {
        Campaign campaign = even("c-1");

        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.FOUND, campaign, NOON, 0, null, 3))).reason())
                .isEqualTo(ReasonCode.SPEND_DATA_STALE);
        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.FOUND, campaign, NOON, 0, NOON - 10_001, 3))).reason())
                .isEqualTo(ReasonCode.SPEND_DATA_STALE);
        assertThat(single(request("q"), state(new CampaignState("c-1", ConfigStatus.FOUND, campaign, NOON, 0, NOON - 10_000, 3))).reason())
                .isEqualTo(ReasonCode.ELIGIBLE);
    }

    @Test
    void budgetIsExhaustedOnceOnlyTheSafetyMarginRemains() {
        assertThat(single(request("q"), state(fresh(asap("c-1"), 99_499_999))).reason()).isEqualTo(ReasonCode.ELIGIBLE);
        assertThat(single(request("q"), state(fresh(asap("c-1"), 99_500_000))).reason()).isEqualTo(ReasonCode.BUDGET_EXHAUSTED);
    }

    @Test
    void evenPacingThrottlesCampaignsAheadOfScheduleButAsapDoesNot() {
        assertThat(single(request("q"), state(fresh(even("c-1"), 57_000_000))).reason()).isEqualTo(ReasonCode.PACING_THROTTLED);
        assertThat(single(request("q"), state(fresh(asap("c-1"), 57_000_000))).reason()).isEqualTo(ReasonCode.ELIGIBLE);
    }

    @Test
    void nearThePacingLineTrafficIsThinnedDeterministically() {
        // 1M of headroom inside a 2M ramp: about half of all requests should serve.
        ServingState nearLine = state(fresh(even("c-1"), 56_000_000));
        Map<ReasonCode, Long> outcomes = IntStream.range(0, 4_000)
                .mapToObj(i -> single(request("r-" + i, "q", "c-1"), nearLine).reason())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(outcomes.get(ReasonCode.ELIGIBLE)).isBetween(1_800L, 2_200L);
        assertThat(outcomes.get(ReasonCode.PACING_THROTTLED)).isBetween(1_800L, 2_200L);

        EligibilityRequest request = request("r-42", "q", "c-1");
        assertThat(engine.evaluate(request, nearLine)).isEqualTo(engine.evaluate(request, nearLine));
    }

    @Test
    void policyIsReportedBeforeDataFreshnessAndEachCandidateGetsItsOwnReason() {
        Campaign stalePolicyBlocked = even("c-1");
        CampaignState staleSpend = new CampaignState("c-1", ConfigStatus.FOUND, stalePolicyBlocked, NOON, 0, NOON - 60_000, 0);

        assertThat(single(request("free stuff"), state(staleSpend)).reason()).isEqualTo(ReasonCode.POLICY_NEGATIVE_KEYWORD);

        List<Decision> decisions = engine.evaluate(request("r-1", "photo editor", "c-1", "c-2", "c-3"), state(
                fresh(even("c-1"), 0),
                fresh(asap("c-2"), BUDGET),
                new CampaignState("c-3", ConfigStatus.NOT_FOUND, null, NOON, 0, NOON, 0)));
        assertThat(decisions).extracting(Decision::reason)
                .containsExactly(ReasonCode.ELIGIBLE, ReasonCode.BUDGET_EXHAUSTED, ReasonCode.CAMPAIGN_NOT_FOUND);
    }

    private Decision single(EligibilityRequest request, ServingState state) {
        List<Decision> decisions = engine.evaluate(request, state);
        assertThat(decisions).hasSize(1);
        return decisions.get(0);
    }

    private static EligibilityRequest request(String query) {
        return request("r-1", query, "c-1");
    }

    private static EligibilityRequest request(String requestId, String query, String... campaignIds) {
        List<Candidate> candidates = Arrays.stream(campaignIds).map(id -> new Candidate("ad-" + id, id)).toList();
        return new EligibilityRequest(requestId, Placement.SEARCH_RESULTS, query, new Device("d-1", APP_STORE_UA), candidates);
    }

    private static Campaign even(String id) {
        return new Campaign(id, CampaignStatus.ACTIVE, BUDGET, PacingMode.EVEN, Set.of(), List.of("free", "cheap game"));
    }

    private static Campaign asap(String id) {
        return new Campaign(id, CampaignStatus.ACTIVE, BUDGET, PacingMode.ASAP, Set.of(), List.of());
    }

    private static CampaignState fresh(Campaign campaign, long spentMicros) {
        return new CampaignState(campaign.id(), ConfigStatus.FOUND, campaign, NOON - 1_000, spentMicros, NOON - 2_000, 0);
    }

    private static ServingState state(CampaignState... campaigns) {
        return stateWithDeviceRequests(1, campaigns);
    }

    private static ServingState stateWithDeviceRequests(long deviceRequests, CampaignState... campaigns) {
        Map<String, CampaignState> byId = Arrays.stream(campaigns).collect(Collectors.toMap(CampaignState::campaignId, Function.identity()));
        return new ServingState(NOON, DependencyStatus.OK, "", deviceRequests, byId);
    }
}
