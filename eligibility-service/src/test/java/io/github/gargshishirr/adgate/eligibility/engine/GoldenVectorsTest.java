package io.github.gargshishirr.adgate.eligibility.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.gargshishirr.adgate.common.SpendPartitioner;
import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.CampaignStatus;
import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Candidate;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Device;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.domain.Placement;
import io.github.gargshishirr.adgate.eligibility.domain.ReasonCode;

/**
 * Golden vectors for the TypeScript port of the engine in {@code playground/src/engine}. This runs the real engine
 * (and the sampler, query normalizer, pacing math and spend partitioner) over a broad, deterministic set of inputs
 * and checks the results against {@code playground/src/engine/golden.json}, which the playground's tests replay
 * against the port. Any change in engine behaviour therefore fails here until the file is regenerated, and the
 * regenerated file fails the playground's tests until the port matches again.
 *
 * <p>Regenerate with:
 * {@code ./mvnw -q -pl eligibility-service -am test -Dtest=GoldenVectorsTest -Dgolden.write=true -Dsurefire.failIfNoSpecifiedTests=false}
 */
class GoldenVectorsTest {

    static final String REGENERATE = "./mvnw -q -pl eligibility-service -am test -Dtest=GoldenVectorsTest "
            + "-Dgolden.write=true -Dsurefire.failIfNoSpecifiedTests=false";

    private static final long DAY = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;
    private static final long NOON = DAY + 12 * HOUR;
    private static final long BUDGET = 100_000_000L;
    private static final String HUMAN_UA = "AppStore/3.0 iOS/19.1";
    private static final List<String> NEGATIVES = List.of("free", "cheap game");

    /** Non-ASCII characters are escaped, so the file is plain ASCII and survives any editor or encoding. */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final List<EngineVector> engineVectors = new ArrayList<>();

    // ---- JSON shapes (the playground reads exactly these field names) ----

    record SettingsSpec(long maxSpendStalenessMillis, long maxConfigAgeMillis, long deviceRequestLimit,
                        long deviceRateWindowMillis, double pacingBurst, double pacingEarlyDayAllowance,
                        double pacingRampFraction, double budgetSafetyMargin, List<String> blockedUserAgentTokens) {

        EngineSettings toSettings() {
            return new EngineSettings(Duration.ofMillis(maxSpendStalenessMillis), Duration.ofMillis(maxConfigAgeMillis),
                    deviceRequestLimit, Duration.ofMillis(deviceRateWindowMillis), pacingBurst, pacingEarlyDayAllowance,
                    pacingRampFraction, budgetSafetyMargin, blockedUserAgentTokens);
        }
    }

    record CampaignSpec(String id, CampaignStatus status, long dailyBudgetMicros, PacingMode pacing,
                        List<Placement> placements, List<String> negativeKeywords) {

        Campaign toCampaign() {
            return new Campaign(id, status, dailyBudgetMicros, pacing, Set.copyOf(placements), negativeKeywords);
        }
    }

    record CampaignStateSpec(String campaignId, ConfigStatus configStatus, CampaignSpec campaign, long configLoadedAtMillis,
                             long spentTodayMicros, Long spendWatermarkMillis, int spendPartition) {

        CampaignState toState() {
            return new CampaignState(campaignId, configStatus, campaign == null ? null : campaign.toCampaign(),
                    configLoadedAtMillis, spentTodayMicros, spendWatermarkMillis, spendPartition);
        }
    }

    record StateSpec(long nowMillis, DependencyStatus dependencyStatus, String dependencyDetail,
                     long deviceRequestsInWindow, List<CampaignStateSpec> campaigns) {

        ServingState toState() {
            Map<String, CampaignState> byId = new LinkedHashMap<>();
            campaigns.forEach(campaign -> byId.put(campaign.campaignId(), campaign.toState()));
            return new ServingState(nowMillis, dependencyStatus, dependencyDetail, deviceRequestsInWindow, byId);
        }
    }

    record DeviceSpec(String id, String userAgent) {
    }

    record CandidateSpec(String adId, String campaignId) {
    }

    record RequestSpec(String requestId, Placement placement, String query, DeviceSpec device, List<CandidateSpec> candidates) {

        EligibilityRequest toRequest() {
            return new EligibilityRequest(requestId, placement, query, new Device(device.id(), device.userAgent()),
                    candidates.stream().map(c -> new Candidate(c.adId(), c.campaignId())).toList());
        }
    }

    record DecisionSpec(String adId, String campaignId, boolean eligible, ReasonCode reason, String detail) {

        static DecisionSpec of(Decision decision) {
            return new DecisionSpec(decision.adId(), decision.campaignId(), decision.eligible(), decision.reason(), decision.detail());
        }
    }

    /** {@code settings} is omitted when the vector uses {@link EngineSettings#defaults()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EngineVector(String name, SettingsSpec settings, RequestSpec request, StateSpec state, List<DecisionSpec> decisions) {
    }

    record SamplerVector(String requestId, String campaignId, double unit) {
    }

    record TokensVector(String text, List<String> tokens) {
    }

    record StemVector(String token, String stem) {
    }

    record PacingVector(long epochMillis, double dayFraction, long dailyBudgetMicros, long allowanceMicros) {
    }

    record PartitionVector(String campaignId, int partitions, int partition) {
    }

    // ---- The test ----

    @Test
    void enginePortMatchesTheJavaEngine() throws IOException {
        handWrittenScenarios();
        randomScenarios(new Random(20_261_119L), 240);

        Map<String, List<?>> sections = new LinkedHashMap<>();
        sections.put("engine", engineVectors);
        sections.put("sampler", samplerVectors());
        sections.put("tokens", tokenVectors());
        sections.put("stems", stemVectors());
        sections.put("pacing", pacingVectors());
        sections.put("partitions", partitionVectors());
        String generated = render(sections);

        Set<ReasonCode> covered = engineVectors.stream()
                .flatMap(vector -> vector.decisions().stream())
                .map(DecisionSpec::reason)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ReasonCode.class)));
        assertThat(covered).as("every reason code has a golden vector").containsExactlyInAnyOrder(ReasonCode.values());

        Path file = goldenFile();
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(file)) {
            fail("Golden vectors file %s is missing. Generate it with: %s", file, REGENERATE);
        }
        JsonNode expected = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode actual = mapper.readTree(generated);
        for (String section : sections.keySet()) {
            JsonNode expectedSection = expected.path(section);
            JsonNode actualSection = actual.path(section);
            for (int i = 0; i < Math.max(expectedSection.size(), actualSection.size()); i++) {
                if (!expectedSection.path(i).equals(actualSection.path(i))) {
                    fail("""
                            %s is out of date: %s[%d] differs.
                              in file:  %s
                              now:      %s
                            If the engine change is intended, regenerate the file with
                              %s
                            and make the TypeScript port in playground/src/engine match it again.""",
                            file, section, i, expectedSection.path(i), actualSection.path(i), REGENERATE);
                }
            }
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static Path goldenFile() {
        String override = System.getProperty("golden.file");
        if (override != null) {
            return Path.of(override);
        }
        Path fromModule = Path.of("..", "playground", "src", "engine", "golden.json");
        Path fromRoot = Path.of("playground", "src", "engine", "golden.json");
        return Files.isDirectory(Path.of("..", "playground")) || !Files.isDirectory(Path.of("playground")) ? fromModule : fromRoot;
    }

    /** One vector per line, so a behaviour change shows up as a readable diff. */
    private String render(Map<String, List<?>> sections) throws IOException {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"about\": ").append(mapper.writeValueAsString(
                "Golden vectors from the Java engine (GoldenVectorsTest). Do not edit by hand. Regenerate with: " + REGENERATE))
                .append(",\n");
        int remaining = sections.size();
        for (Map.Entry<String, List<?>> section : sections.entrySet()) {
            out.append("  ").append(mapper.writeValueAsString(section.getKey())).append(": [\n");
            List<?> vectors = section.getValue();
            for (int i = 0; i < vectors.size(); i++) {
                out.append("    ").append(mapper.writeValueAsString(vectors.get(i))).append(i + 1 < vectors.size() ? ",\n" : "\n");
            }
            out.append("  ]").append(--remaining > 0 ? ",\n" : "\n");
        }
        return out.append("}\n").toString();
    }

    // ---- Engine scenarios ----

    private void add(String name, RequestSpec request, StateSpec state) {
        add(name, null, request, state);
    }

    private void add(String name, SettingsSpec settings, RequestSpec request, StateSpec state) {
        EligibilityEngine engine = new EligibilityEngine(settings == null ? EngineSettings.defaults() : settings.toSettings());
        List<DecisionSpec> decisions = engine.evaluate(request.toRequest(), state.toState()).stream().map(DecisionSpec::of).toList();
        engineVectors.add(new EngineVector(name, settings, request, state, decisions));
    }

    private void handWrittenScenarios() {
        dependencyScenarios();
        trafficScenarios();
        configurationScenarios();
        policyScenarios();
        negativeKeywordScenarios();
        spendFreshnessScenarios();
        budgetScenarios();
        pacingScenarios();
        multiCandidateScenarios();
        customSettingsScenarios();
    }

    private void dependencyScenarios() {
        CampaignStateSpec[] fresh = {fresh(even("c-1"), NOON, 0), fresh(asap("c-2"), NOON, 0), fresh(even("c-3"), NOON, 0)};
        RequestSpec request = request("r-dep", "photo editor", "c-1", "c-2", "c-3");
        add("redis unavailable declines every candidate", request,
                state(NOON, DependencyStatus.UNAVAILABLE, "RedisConnectionFailureException: Unable to connect to Redis", 1, fresh));
        add("open circuit declines every candidate", request,
                state(NOON, DependencyStatus.CIRCUIT_OPEN, "circuit breaker 'redis' is open", 1, fresh));
        add("latency budget exceeded declines every candidate", request,
                state(NOON, DependencyStatus.DEADLINE_EXCEEDED, "reading serving state took 63.2 ms (budget 50 ms)", 1, fresh));
        add("deadline exceeded without a detail", request, state(NOON, DependencyStatus.DEADLINE_EXCEEDED, null, 1, fresh));
        add("unavailable without a detail", request, state(NOON, DependencyStatus.UNAVAILABLE, null, 1, fresh));
        add("dependency failure is reported before invalid traffic",
                requestWith("r-dep-bot", Placement.SEARCH_RESULTS, "q", "d-1", "ExampleBot/2.1", "c-1"),
                state(NOON, DependencyStatus.CIRCUIT_OPEN, "circuit breaker 'redis' is open", 99, fresh(even("c-1"), NOON, 0)));
        add("dependency failure with no candidates in the snapshot", request("r-dep-empty", "q", "c-9"),
                state(NOON, DependencyStatus.UNAVAILABLE, "RedisCommandTimeoutException: Command timed out after 40 millisecond(s)", 0));
    }

    private void trafficScenarios() {
        CampaignStateSpec campaign = fresh(even("c-1"), NOON, 0);
        String[] userAgents = {
                "Mozilla/5.0 (compatible; ExampleBot/2.1)",
                "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/131.0.0.0 Safari/537.36",
                "python-requests/2.32.3",
                "Some CRAWLER v1",
                "Baiduspider+(+http://www.baidu.com/search/spider.htm)",
                "SpiderBot/1.0",
                "Cubot P80 Android 13",
                "Abbott-Health/2.0 iOS/19.1",
                "İOS BOT",
                "AppStore/3.0 iOS/19.1",
                "AppStore/3.0 iPadOS/19.1",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 19_1 like Mac OS X)",
                "   ",
                "",
                "\u00A0",
        };
        for (int i = 0; i < userAgents.length; i++) {
            add("user agent: " + userAgents[i].strip(),
                    requestWith("r-ua-" + i, Placement.SEARCH_RESULTS, "photo editor", "d-" + i, userAgents[i], "c-1"),
                    state(NOON, 1, campaign));
        }
        add("user agent: none sent", requestWith("r-ua-null", Placement.SEARCH_RESULTS, "photo editor", "d-1", null, "c-1"),
                state(NOON, 1, campaign));
        for (long count : new long[] {0, 1, 19, 20, 21, 1_000}) {
            add("device made " + count + " requests in the window", request("r-rate-" + count, "photo editor", "c-1"),
                    state(NOON, count, campaign));
        }
        add("invalid user agent is reported before the rate limit",
                requestWith("r-ua-rate", Placement.SEARCH_RESULTS, "q", "d-1", "python-requests/2.32.3", "c-1"),
                state(NOON, 50, campaign));
        add("rate limit is reported before a missing campaign", request("r-rate-missing", "q", "c-missing"),
                state(NOON, 21, cs("c-missing", ConfigStatus.NOT_FOUND, null, NOON, 0, NOON, 1)));
    }

    private void configurationScenarios() {
        CampaignSpec campaign = even("c-1");
        add("campaign absent from the snapshot", request("r-cfg-absent", "q", "c-1"), state(NOON, 1));
        add("prototype-like campaign id absent from the snapshot", request("r-cfg-proto", "q", "constructor"), state(NOON, 1));
        add("prototype-like campaign id present in the snapshot", request("r-cfg-proto2", "q", "constructor"),
                state(NOON, 1, fresh(even("constructor"), NOON, 0)));
        add("configuration unavailable", request("r-cfg-unavailable", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.UNAVAILABLE, null, NOON, 0, NOON, 0)));
        add("configuration not found", request("r-cfg-missing", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.NOT_FOUND, null, NOON, 0, NOON, 0)));
        add("not found is reported before configuration age", request("r-cfg-missing-old", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.NOT_FOUND, null, NOON - HOUR, 0, NOON, 0)));
        for (long age : new long[] {0, 1_999, 29_999, 30_000, 30_001, 45_250, 3_600_000, -5_000}) {
            add("configuration loaded " + age + " ms ago", request("r-cfg-age-" + age, "q", "c-1"),
                    state(NOON, 1, cs("c-1", ConfigStatus.FOUND, campaign, NOON - age, 0, NOON - 500, 2)));
        }
        add("stale configuration is reported before a paused status", request("r-cfg-stale-paused", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.FOUND, paused("c-1"), NOON - 31_000, 0, NOON, 0)));
    }

    private void policyScenarios() {
        add("paused campaign", request("r-paused", "photo editor", "c-1"), state(NOON, 1, fresh(paused("c-1"), NOON, 0)));
        add("paused is reported before placement and keywords",
                requestWith("r-paused-policy", Placement.PRODUCT_PAGES, "free games", "d-1", HUMAN_UA, "c-1"),
                state(NOON, 1, fresh(new CampaignSpec("c-1", CampaignStatus.PAUSED, BUDGET, PacingMode.EVEN,
                        List.of(Placement.TODAY_TAB), NEGATIVES), NOON, 0)));
        for (Placement placement : Placement.values()) {
            add("no placement restriction, request on " + placement,
                    requestWith("r-place-all-" + placement, placement, "photo editor", "d-1", HUMAN_UA, "c-1"),
                    state(NOON, 1, fresh(even("c-1"), NOON, 0)));
            add("search-only campaign, request on " + placement,
                    requestWith("r-place-search-" + placement, placement, "photo editor", "d-1", HUMAN_UA, "c-2"),
                    state(NOON, 1, fresh(new CampaignSpec("c-2", CampaignStatus.ACTIVE, BUDGET, PacingMode.ASAP,
                            List.of(Placement.SEARCH_RESULTS, Placement.SEARCH_TAB), List.of()), NOON, 0)));
        }
        add("placement is reported before negative keywords",
                requestWith("r-place-before-kw", Placement.TODAY_TAB, "free games", "d-1", HUMAN_UA, "c-1"),
                state(NOON, 1, fresh(new CampaignSpec("c-1", CampaignStatus.ACTIVE, BUDGET, PacingMode.EVEN,
                        List.of(Placement.SEARCH_RESULTS), NEGATIVES), NOON, 0)));
    }

    private void negativeKeywordScenarios() {
        Object[][] cases = {
                {"FREE photo editor", NEGATIVES},
                {"free-to-play", NEGATIVES},
                {"Cheap GAMES!!", NEGATIVES},
                {"freestyle", NEGATIVES},
                {"cheap fun game", NEGATIVES},
                {"cheap...game", NEGATIVES},
                {"ＦＲＥＥ Café, Photo-Editor!", NEGATIVES},
                {"🔥free🔥 stickers", NEGATIVES},
                {"Gamés gratuits", List.of("games")},
                {"puzzle", List.of("puzzles")},
                {"story time", List.of("stories")},
                {"box office", List.of("boxes")},
                {"match 3", List.of("matches")},
                {"reading glasses", List.of("glass")},
                {"city buses", List.of("bus")},
                {"new games", List.of("news")},
                {"this is it", List.of("is")},
                {"anything at all", List.of("")},
                {"anything at all", List.of("   ")},
                {"anything at all", List.of("!!!")},
                {null, NEGATIVES},
                {"", NEGATIVES},
                {"   ", NEGATIVES},
                {"ﬁtness tracker", List.of("fitness")},
                {"İstanbul tours", List.of("istanbul")},
                {"ΦΘΗΝΟΣ ΤΑΞΙΔΙ", List.of("φθηνος")},
                {"ΦΘΗΝΟΣ ΤΑΞΙΔΙ", List.of("φθηνοσ")},
                {"Straße map", List.of("strasse")},
                {"4K video editor", List.of("4k")},
                {"level ٣ guide", List.of("٣")},
                {"free trial today", List.of("cheap", "Free Trial", "free")},
                {"cafe\u0301 finder", List.of("café")},
                {"ｶﾒﾗ アプリ", List.of("カメラ")},
                {"사진 편집", List.of("사진")},
                {"budget   planner\tpro", List.of("budget planner")},
                {"photo_editor", List.of("photo editor")},
                {"x-ray vision", List.of("xray")},
                {"games", List.of("game", "games")},
        };
        for (int i = 0; i < cases.length; i++) {
            String query = (String) cases[i][0];
            @SuppressWarnings("unchecked")
            List<String> negatives = (List<String>) cases[i][1];
            CampaignSpec campaign = new CampaignSpec("c-kw", CampaignStatus.ACTIVE, BUDGET, PacingMode.ASAP, List.of(), negatives);
            add("negative keywords " + negatives + " vs query " + (query == null ? "null" : "'" + query + "'"),
                    request("r-kw-" + i, query, "c-kw"), state(NOON, 1, fresh(campaign, NOON, 0)));
        }
    }

    private void spendFreshnessScenarios() {
        CampaignSpec campaign = even("c-1");
        add("no spend watermark yet", request("r-spend-none", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.FOUND, campaign, NOON, 0, null, 3)));
        for (long lag : new long[] {0, 450, 9_999, 10_000, 10_001, 63_000, -2_000}) {
            add("spend watermark " + lag + " ms behind", request("r-spend-lag-" + lag, "q", "c-1"),
                    state(NOON, 1, cs("c-1", ConfigStatus.FOUND, campaign, NOON, 0, NOON - lag, 4)));
        }
        add("negative keyword is reported before stale spend", request("r-spend-kw", "free stuff", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.FOUND, campaign, NOON, 0, NOON - 60_000, 0)));
        add("stale spend is reported before an exhausted budget", request("r-spend-budget", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.FOUND, asap("c-1"), NOON, BUDGET * 2, NOON - 60_000, 5)));
    }

    private void budgetScenarios() {
        long[][] cases = {
                {BUDGET, 0}, {BUDGET, 99_499_999}, {BUDGET, 99_500_000}, {BUDGET, BUDGET}, {BUDGET, 250_000_000},
                {BUDGET, 12_350_000}, {BUDGET, 33_333_333}, {BUDGET, 66_650_000},
                {1, 0}, {3, 1}, {3, 2}, {199, 197}, {199, 198}, {201, 198}, {201, 199},
                {1_005_000, 1_005_000}, {2_675_000, 2_675_000}, {8_000_000, 7_960_000}, {8_000_000, 7_959_999},
                {20_000_000, 19_900_000}, {10_000_000_000_000L, 9_950_000_000_000L}, {10_000_000_000_000L, 9_949_999_999_999L},
                {123_456_789, 122_839_505}, {123_456_789, 122_839_504},
        };
        for (long[] c : cases) {
            add("ASAP budget " + c[0] + " with " + c[1] + " spent", request("r-budget-" + c[0] + "-" + c[1], "q", "c-b"),
                    state(NOON, 1, fresh(new CampaignSpec("c-b", CampaignStatus.ACTIVE, c[0], PacingMode.ASAP, List.of(), List.of()), NOON, c[1])));
        }
    }

    private void pacingScenarios() {
        long[] times = {
                DAY, DAY + 1, DAY + 6 * HOUR, DAY + 9 * HOUR + 5 * 60_000 + 59_999, DAY + 11 * HOUR + 14 * 60_000, NOON,
                DAY + 18 * HOUR, DAY + 21 * HOUR + 49 * 60_000, DAY + 24 * HOUR - 1, -1L, 0L,
        };
        double[] spendShares = {0.0, 0.25, 0.5, 0.9, 1.2};
        for (long now : times) {
            long allowance = PacingPolicy.evenPacingAllowanceMicros(BUDGET, PacingPolicy.dayFraction(now), 0.10, 0.02);
            long ramp = (long) (BUDGET * 0.02);
            Set<Long> spends = new LinkedHashSet<>(List.of(
                    0L, allowance - ramp - 1, allowance - ramp, allowance - ramp + 1, allowance - ramp / 2, allowance - 1, allowance,
                    allowance + 1));
            for (long spent : spends) {
                if (spent < 0) {
                    continue;
                }
                add("even pacing at " + Instant.ofEpochMilli(now) + " with " + spent + " spent",
                        request("r-pace-" + now + "-" + spent, "q", "c-even"),
                        state(now, 1, fresh(new CampaignSpec("c-even", CampaignStatus.ACTIVE, BUDGET, PacingMode.EVEN, List.of(), List.of()), now, spent)));
            }
            for (double share : spendShares) {
                long spent = (long) (BUDGET * share);
                add("ASAP at " + Instant.ofEpochMilli(now) + " with " + spent + " spent",
                        request("r-asap-" + now + "-" + spent, "q", "c-asap"),
                        state(now, 1, fresh(asap("c-asap"), now, spent)));
            }
        }
        // Thinning near the pacing line: the deterministic draw decides, so many request ids are needed.
        long[] headrooms = {1_000_000, 250_000, 690_000, 1_999_999, 1, 1_500_000};
        for (long headroom : headrooms) {
            long spent = 57_000_000 - headroom;
            for (int i = 0; i < 24; i++) {
                add("near the pacing line with " + headroom + " headroom, request r-" + i,
                        request("r-" + i, "q", "c-1"), state(NOON, 1, fresh(even("c-1"), NOON, spent)));
            }
        }
        for (String requestId : List.of("9f1c2d3e-0000-4a5b-8c7d-112233445566", "req-zz", "Aa", "BB", "r-42")) {
            add("near the pacing line, request " + requestId, request(requestId, "q", "lumen-fitness"),
                    state(NOON, 1, fresh(new CampaignSpec("lumen-fitness", CampaignStatus.ACTIVE, 400_000_000, PacingMode.EVEN,
                            List.of(), List.of()), NOON, 224_000_000)));
        }
        add("tiny even budget has no ramp", request("r-tiny-ramp", "q", "c-t"),
                state(NOON, 1, fresh(new CampaignSpec("c-t", CampaignStatus.ACTIVE, 49, PacingMode.EVEN, List.of(), List.of()), NOON, 26)));
        add("tiny even budget at its pacing line", request("r-tiny-line", "q", "c-t"),
                state(NOON, 1, fresh(new CampaignSpec("c-t", CampaignStatus.ACTIVE, 49, PacingMode.EVEN, List.of(), List.of()), NOON, 27)));
        add("budget exhaustion is reported before pacing", request("r-budget-before-pacing", "q", "c-1"),
                state(NOON, 1, fresh(even("c-1"), NOON, 99_600_000)));
    }

    private void multiCandidateScenarios() {
        add("each candidate gets its own reason", request("r-1", "photo editor", "c-1", "c-2", "c-3"), state(NOON, 1,
                fresh(even("c-1"), NOON, 0),
                fresh(asap("c-2"), NOON, BUDGET),
                cs("c-3", ConfigStatus.NOT_FOUND, null, NOON, 0, NOON, 0)));
        add("the same campaign twice gets the same answer", new RequestSpec("r-dup", Placement.SEARCH_RESULTS, "q",
                        new DeviceSpec("d-1", HUMAN_UA), List.of(new CandidateSpec("ad-a", "c-1"), new CandidateSpec("ad-b", "c-1"))),
                state(NOON, 1, fresh(even("c-1"), NOON, 56_000_000)));
        add("five candidates, five reasons", request("r-mixed", "cheap games", "c-1", "c-2", "c-3", "c-4", "c-5"), state(NOON, 3,
                fresh(even("c-1"), NOON, 0),
                fresh(paused("c-2"), NOON, 0),
                cs("c-3", ConfigStatus.FOUND, asap("c-3"), NOON, 0, NOON - 30_000, 3),
                fresh(asap("c-4"), NOON, 10_000_000),
                fresh(new CampaignSpec("c-5", CampaignStatus.ACTIVE, BUDGET, PacingMode.EVEN, List.of(Placement.TODAY_TAB), List.of()),
                        NOON, 0)));
    }

    private void customSettingsScenarios() {
        SettingsSpec custom = new SettingsSpec(5_000, 60_000, 5, 60_000, 0.20, 0.05, 0.10, 0.01, List.of("curl", "Wget"));
        CampaignStateSpec campaign = cs("c-1", ConfigStatus.FOUND, even("c-1"), NOON - 45_000, 0, NOON - 4_000, 1);
        add("custom settings: curl is automation", custom,
                requestWith("r-custom-curl", Placement.SEARCH_RESULTS, "q", "d-1", "curl/8.4.0", "c-1"), state(NOON, 1, campaign));
        add("custom settings: token keeps its configured case", custom,
                requestWith("r-custom-wget", Placement.SEARCH_RESULTS, "q", "d-1", "WGET/1.21", "c-1"), state(NOON, 1, campaign));
        add("custom settings: bots are not blocked", custom,
                requestWith("r-custom-bot", Placement.SEARCH_RESULTS, "q", "d-1", "ExampleBot/2.1", "c-1"), state(NOON, 1, campaign));
        add("custom settings: lower rate limit and longer window", custom, request("r-custom-rate", "q", "c-1"), state(NOON, 6, campaign));
        add("custom settings: stricter spend staleness", custom, request("r-custom-spend", "q", "c-1"),
                state(NOON, 1, cs("c-1", ConfigStatus.FOUND, even("c-1"), NOON, 0, NOON - 5_001, 1)));
        for (long spent : new long[] {0, 55_000_000, 60_000_000, 64_999_999, 65_000_000, 99_000_000}) {
            add("custom settings: pacing with " + spent + " spent", custom, request("r-custom-pace-" + spent, "q", "c-1"),
                    state(NOON, 1, cs("c-1", ConfigStatus.FOUND, even("c-1"), NOON, spent, NOON, 1)));
        }
    }

    /** Broad coverage: random combinations of every input, from a fixed seed. */
    private void randomScenarios(Random random, int count) {
        String[] queries = {
                "photo editor", "budget planner", "fitness tracker", "puzzle games", "free games", "music player",
                "cheap flights", "meditation", "recipe app", "language learning", "Free Photo Editor", "CHEAP games!",
                "home workouts", "trail maps", "offline maps", "piano lessons", "cafés near me", null, "",
        };
        String[] userAgents = {HUMAN_UA, HUMAN_UA, HUMAN_UA, HUMAN_UA, HUMAN_UA, "AppStore/3.0 iPadOS/19.1", "AppStore/3.0 iPadOS/19.1",
                null, "ExampleBot/2.1", "HeadlessChrome/131"};
        String[] keywordPool = {"free", "cheap", "cheap flights", "games", "trial", "editor", "maps", "lessons"};
        long[] budgets = {1, 49, 199, 1_005_000, 2_675_000, 8_000_000, 20_000_000, 120_000_000, 400_000_000, 10_000_000_000_000L};
        String[] campaignIds = {"c-1", "c-2", "c-3", "c-4", "c-5", "c-6", "lumen-fitness", "pixelforge"};
        DependencyStatus[] failures = {DependencyStatus.UNAVAILABLE, DependencyStatus.CIRCUIT_OPEN, DependencyStatus.DEADLINE_EXCEEDED};

        for (int n = 0; n < count; n++) {
            long now = DAY + (random.nextInt(4) == 0 ? between(random, -HOUR, HOUR) : between(random, 0, 24 * HOUR));
            DependencyStatus dependency = random.nextInt(12) == 0 ? failures[random.nextInt(failures.length)] : DependencyStatus.OK;
            String dependencyDetail = dependency == DependencyStatus.OK ? "" : "simulated " + dependency.name().toLowerCase() + " #" + n;
            long deviceRequests = random.nextInt(10) == 0 ? 15 + random.nextInt(10) : 1 + random.nextInt(5);

            int candidateCount = 1 + random.nextInt(4);
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < candidateCount; i++) {
                chosen.add(campaignIds[random.nextInt(campaignIds.length)]);
            }
            List<CampaignStateSpec> campaigns = new ArrayList<>();
            for (String id : chosen.stream().distinct().toList()) {
                int kind = random.nextInt(40);
                if (kind == 0) {
                    continue; // absent from the snapshot
                }
                if (kind == 1) {
                    campaigns.add(cs(id, ConfigStatus.UNAVAILABLE, null, now, 0, null, 0));
                    continue;
                }
                if (kind <= 3) {
                    campaigns.add(cs(id, ConfigStatus.NOT_FOUND, null, now, 0, now, 0));
                    continue;
                }
                long budget = budgets[random.nextInt(budgets.length)];
                PacingMode pacing = random.nextInt(5) < 3 ? PacingMode.EVEN : PacingMode.ASAP;
                List<Placement> placements = new ArrayList<>();
                if (random.nextBoolean()) {
                    for (Placement placement : Placement.values()) {
                        if (random.nextInt(3) > 0) {
                            placements.add(placement);
                        }
                    }
                }
                List<String> negatives = new ArrayList<>();
                for (String keyword : keywordPool) {
                    if (random.nextInt(6) == 0) {
                        negatives.add(keyword);
                    }
                }
                CampaignStatus status = random.nextInt(12) == 0 ? CampaignStatus.PAUSED : CampaignStatus.ACTIVE;
                CampaignSpec campaign = new CampaignSpec(id, status, budget, pacing, placements, negatives);

                long allowance = PacingPolicy.evenPacingAllowanceMicros(budget, PacingPolicy.dayFraction(now), 0.10, 0.02);
                long ramp = (long) (budget * 0.02);
                long spent = random.nextInt(5) < 2
                        ? Math.max(0, allowance - (long) (random.nextDouble() * 1.2 * Math.max(1, ramp)))
                        : (long) (budget * random.nextDouble() * 1.05);
                long configAge = random.nextInt(15) == 0 ? 25_000 + random.nextInt(10_000) : random.nextInt(2_500);
                Long watermark = random.nextInt(25) == 0 ? null
                        : now - (random.nextInt(15) == 0 ? 8_000 + random.nextInt(5_000) : random.nextInt(1_500));
                campaigns.add(cs(id, ConfigStatus.FOUND, campaign, now - configAge, spent, watermark,
                        SpendPartitioner.partitionFor(id, 6)));
            }

            List<CandidateSpec> candidates = new ArrayList<>();
            for (int i = 0; i < chosen.size(); i++) {
                candidates.add(new CandidateSpec("ad-" + n + "-" + i, chosen.get(i)));
            }
            Placement placement = Placement.values()[random.nextInt(Placement.values().length)];
            String query = queries[random.nextInt(queries.length)];
            String userAgent = userAgents[random.nextInt(userAgents.length)];
            String requestId = random.nextBoolean() ? new UUID(random.nextLong(), random.nextLong()).toString() : "req-" + n;
            add("random #" + n,
                    new RequestSpec(requestId, placement, query, new DeviceSpec("device-" + random.nextInt(500), userAgent), candidates),
                    new StateSpec(now, dependency, dependencyDetail, deviceRequests, campaigns));
        }
    }

    // ---- Other ported functions ----

    private static List<SamplerVector> samplerVectors() {
        List<String> requestIds = new ArrayList<>(List.of("", "r-0", "r-1", "r-42", "Aa", "BB", "req-zz", "日本語", "😀",
                "\uD800", "a".repeat(300), "9f1c2d3e-0000-4a5b-8c7d-112233445566"));
        Random random = new Random(42);
        for (int i = 0; i < 40; i++) {
            requestIds.add(new UUID(random.nextLong(), random.nextLong()).toString());
        }
        List<String> campaignIds = List.of("c-1", "", "lumen-fitness", "Ω");
        List<SamplerVector> vectors = new ArrayList<>();
        for (int i = 0; i < requestIds.size(); i++) {
            String requestId = requestIds.get(i);
            String campaignId = campaignIds.get(i % campaignIds.size());
            vectors.add(new SamplerVector(requestId, campaignId, DeterministicSampler.unit(requestId, campaignId)));
        }
        for (String campaignId : campaignIds) {
            vectors.add(new SamplerVector("r-7", campaignId, DeterministicSampler.unit("r-7", campaignId)));
        }
        return vectors;
    }

    private static List<TokensVector> tokenVectors() {
        List<String> texts = Arrays.asList(null, "", "   ", "\u00A0", "photo editor", "FREE Gamés!", "ＦＲＥＥ Café, Photo-Editor!",
                "free-to-play", "Cheap GAMES!!", "cheap...game", "🔥free🔥 stickers", "ﬁtness", "İstanbul", "ΦΘΗΝΟΣ ΤΑΞΙΔΙ",
                "Straße", "4K video", "level ٣", "cafe\u0301", "ｶﾒﾗ アプリ", "사진 편집", "budget   planner\tpro", "photo_editor",
                "x-ray", "stories boxes matches glasses buses news is bus glass", "ies yes dies flies tries series species",
                "Ⅻ chapters", "½ price", "naïve résumé", "ÅNGSTRÖM", "Œuvres", "ǅungla", "ﬀ ﬃ", "١٢٣ ٤٥", "C++ & C#",
                "e-mail/sms", "a", "as", "ass", "gas", "bus", "buses", "kisses", "foxes", "quizzes", "churches", "wishes",
                "toes", "shoes", "heroes", "classes", "analysis", "virus", "stimulus", "status", "cookies", "movies", "pies");
        return texts.stream().map(text -> new TokensVector(text, QueryNormalizer.tokens(text))).toList();
    }

    private static List<StemVector> stemVectors() {
        return List.of("games", "stories", "boxes", "matches", "glass", "bus", "news", "is", "pies", "ties", "series",
                        "dishes", "buzzes", "taxes", "ss", "s", "", "yes", "axes", "uses", "gases", "this", "does", "plus")
                .stream().map(token -> new StemVector(token, QueryNormalizer.stem(token))).toList();
    }

    private static List<PacingVector> pacingVectors() {
        long[] times = {DAY, DAY + 1, DAY + 6 * HOUR, DAY + 9 * HOUR, NOON, DAY + 18 * HOUR, DAY + 21 * HOUR + 49 * 60_000,
                DAY + 24 * HOUR - 1, -1L, 0L, -86_400_001L, 1_793_446_470_120L};
        long[] budgets = {1, 49, 1_005_000, 20_000_000, 100_000_000, 123_456_789, 10_000_000_000_000L};
        List<PacingVector> vectors = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            for (int j = 0; j < budgets.length; j += (i % 2 == 0 ? 1 : 2)) {
                double fraction = PacingPolicy.dayFraction(times[i]);
                vectors.add(new PacingVector(times[i], fraction, budgets[j],
                        PacingPolicy.evenPacingAllowanceMicros(budgets[j], fraction, 0.10, 0.02)));
            }
        }
        return vectors;
    }

    private static List<PartitionVector> partitionVectors() {
        List<String> ids = new ArrayList<>(List.of("lumen-fitness", "pixelforge", "trailmix-travel", "chorus-music", "c-unknown",
                "constructor", "a", "ab", "abc", "abcd", "abcde", "café", "日本", "x".repeat(128)));
        for (int i = 0; i < 16; i++) {
            ids.add("c-" + i);
        }
        List<PartitionVector> vectors = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int partitions = new int[] {6, 6, 3, 12, 1}[i % 5];
            vectors.add(new PartitionVector(ids.get(i), partitions, SpendPartitioner.partitionFor(ids.get(i), partitions)));
        }
        return vectors;
    }

    // ---- Builders ----

    /** Uses only java.util.Random methods whose algorithm is specified, so the vectors are the same on every JDK. */
    private static long between(Random random, long origin, long bound) {
        return origin + (long) (random.nextDouble() * (bound - origin));
    }

    private static CampaignSpec even(String id) {
        return new CampaignSpec(id, CampaignStatus.ACTIVE, BUDGET, PacingMode.EVEN, List.of(), NEGATIVES);
    }

    private static CampaignSpec asap(String id) {
        return new CampaignSpec(id, CampaignStatus.ACTIVE, BUDGET, PacingMode.ASAP, List.of(), List.of());
    }

    private static CampaignSpec paused(String id) {
        return new CampaignSpec(id, CampaignStatus.PAUSED, BUDGET, PacingMode.EVEN, List.of(), NEGATIVES);
    }

    private static CampaignStateSpec fresh(CampaignSpec campaign, long now, long spentMicros) {
        return new CampaignStateSpec(campaign.id(), ConfigStatus.FOUND, campaign, now - 1_000, spentMicros, now - 2_000, 0);
    }

    private static CampaignStateSpec cs(String id, ConfigStatus status, CampaignSpec campaign, long loadedAt, long spent,
                                        Long watermark, int partition) {
        return new CampaignStateSpec(id, status, campaign, loadedAt, spent, watermark, partition);
    }

    private static StateSpec state(long now, long deviceRequests, CampaignStateSpec... campaigns) {
        return new StateSpec(now, DependencyStatus.OK, "", deviceRequests, List.of(campaigns));
    }

    private static StateSpec state(long now, DependencyStatus status, String detail, long deviceRequests, CampaignStateSpec... campaigns) {
        return new StateSpec(now, status, detail, deviceRequests, List.of(campaigns));
    }

    private static RequestSpec request(String requestId, String query, String... campaignIds) {
        return requestWith(requestId, Placement.SEARCH_RESULTS, query, "d-1", HUMAN_UA, campaignIds);
    }

    private static RequestSpec requestWith(String requestId, Placement placement, String query, String deviceId, String userAgent,
                                           String... campaignIds) {
        List<CandidateSpec> candidates = Arrays.stream(campaignIds).map(id -> new CandidateSpec("ad-" + id, id)).toList();
        return new RequestSpec(requestId, placement, query, new DeviceSpec(deviceId, userAgent), candidates);
    }
}
