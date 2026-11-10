package io.github.gargshishirr.adgate.eligibility.api;

import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectDetailContains;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectReason;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.freshSpendWatermarks;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.putCampaign;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.requestEligibility;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;

@SpringBootTest(properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "adgate.latency-budget=5s",
        "spring.data.redis.timeout=5s",
        "resilience4j.circuitbreaker.instances.redis.slow-call-duration-threshold=5s"
})
@AutoConfigureMockMvc
class EligibilityApiIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();
    private static final String ASAP_CAMPAIGN = """
            {"status":"ACTIVE","dailyBudgetMicros":50000000,"pacing":"ASAP","negativeKeywords":["free"]}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        ApiTestSupport.redisProperties(REDIS, registry);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        REDIS.stop();
    }

    @BeforeEach
    void resetState() throws Exception {
        ApiTestSupport.flush(redis);
        putCampaign(mvc, "c-asap", ASAP_CAMPAIGN);
        freshSpendWatermarks(redis, System.currentTimeMillis());
    }

    @Test
    void servesAndTheDecisionCanBeExplainedAndReplayed() throws Exception {
        expectReason(requestEligibility(mvc, "r-serve", "photo editor", "c-asap"), 0, "ELIGIBLE")
                .andExpect(jsonPath("$.decisions[0].eligible").value(true));

        mvc.perform(get("/v1/decisions/r-serve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dependencyStatus").value("OK"))
                .andExpect(jsonPath("$.state.campaigns.c-asap.spentTodayMicros").value(0));

        mvc.perform(post("/v1/decisions/r-serve/replay").param("currentState", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reproducible").value(true))
                .andExpect(jsonPath("$.current[0].reason").value("ELIGIBLE"));
    }

    @Test
    void declinesOnceTheDailyBudgetIsSpent() throws Exception {
        redis.opsForValue().set(RedisKeys.spend("c-asap", RedisKeys.utcDay(System.currentTimeMillis())), "50000000");

        expectDetailContains(expectReason(requestEligibility(mvc, "r-budget", "photo editor", "c-asap"), 0, "BUDGET_EXHAUSTED"),
                0, "spent 50.00 of a 50.00 daily budget");
    }

    @Test
    void failsClosedWhenSpendDataIsStale() throws Exception {
        freshSpendWatermarks(redis, System.currentTimeMillis() - 60_000);

        expectReason(requestEligibility(mvc, "r-stale", "photo editor", "c-asap"), 0, "SPEND_DATA_STALE");
    }

    @Test
    void reportsOneReasonPerCandidate() throws Exception {
        var result = requestEligibility(mvc, "r-mixed", "free photo editor", "c-asap", "c-missing");

        expectReason(result, 0, "POLICY_NEGATIVE_KEYWORD");
        expectReason(result, 1, "CAMPAIGN_NOT_FOUND");
    }

    @Test
    void unknownDecisionsAreNotFound() throws Exception {
        mvc.perform(get("/v1/decisions/never-seen")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedRequests() throws Exception {
        mvc.perform(post("/v1/eligibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-bad\",\"placement\":\"SEARCH_RESULTS\",\"device\":{\"id\":\"d\"},\"candidates\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminViewShowsLiveSpendAndFreshness() throws Exception {
        redis.opsForValue().set(RedisKeys.spend("c-asap", RedisKeys.utcDay(System.currentTimeMillis())), "12500000");

        mvc.perform(get("/admin/campaigns/c-asap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentTodayMicros").value(12_500_000))
                .andExpect(jsonPath("$.spentPercent").value(25.0))
                .andExpect(jsonPath("$.spendLagMillis").isNumber());
    }
}
