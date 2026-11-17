package io.github.gargshishirr.adgate.eligibility.api;

import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.asAdmin;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.asClient;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.eligibilityBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;

/** Requests that break the input rules are refused with problem+json that names the field but never echoes the value. */
@SpringBootTest(properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "adgate.latency-budget=5s",
        "spring.data.redis.timeout=5s",
        "resilience4j.circuitbreaker.instances.redis.slow-call-duration-threshold=5s"
})
@AutoConfigureMockMvc
class RequestValidationIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.redisProperties(REDIS, registry);
        ApiTestSupport.apiKeyProperties(registry);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        REDIS.stop();
    }

    @BeforeEach
    void resetState() {
        ApiTestSupport.flush(redis);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "c:1                 | candidates[0].campaignId",
            "c-*                 | candidates[0].campaignId",
            "'c 1'               | candidates[0].campaignId",
            "-c                  | candidates[0].campaignId"})
    void unsafeCampaignIdsAreRefused(String campaignId, String field) throws Exception {
        String response = expectInvalid(decide(eligibilityBody("r-1", "photo editor", campaignId)), field)
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(campaignId);
    }

    @Test
    void unsafeRequestAndDeviceIdsAreRefused() throws Exception {
        expectInvalid(decide(eligibilityBody("r:1", "photo editor", "c-1")), "requestId");
        expectInvalid(decide(eligibilityBody("r-1", "photo editor", "c-1").replace("\"device-r-1\"", "\"dev*ce\"")), "device.id");
        expectInvalid(decide(eligibilityBody("r-1", "photo editor", "c-1").replace("\"device-r-1\"", "\"" + "d".repeat(129) + "\"")),
                "device.id");
    }

    @Test
    void tooManyCandidatesAreRefused() throws Exception {
        String[] campaigns = IntStream.rangeClosed(1, 101).mapToObj(i -> "c-" + i).toArray(String[]::new);
        expectInvalid(decide(eligibilityBody("r-many", "photo editor", campaigns)), "candidates");
    }

    @Test
    void bodiesThatAreNotValidJsonAreRefusedWithoutEchoingThem() throws Exception {
        String response = decide("{\"requestId\": <script>alert(1)</script>")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("script");

        decide(eligibilityBody("r-1", "photo editor", "c-1").replace("SEARCH_RESULTS", "NOT_A_PLACEMENT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedBodiesAreRefusedBeforeTheyAreRead() throws Exception {
        String body = eligibilityBody("r-big", " ".repeat(200 * 1024), "c-1");
        decide(body)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unsafeCampaignIdsInAdminPathsAreRefused() throws Exception {
        String campaign = "{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":50000000,\"pacing\":\"ASAP\"}";
        for (String id : new String[] {"-c", "a".repeat(129)}) {
            expectInvalid(mvc.perform(asAdmin(put("/admin/campaigns/" + id)).contentType(MediaType.APPLICATION_JSON)
                    .content(campaign)), "campaignId");
            expectInvalid(mvc.perform(asAdmin(get("/admin/campaigns/" + id))), "campaignId");
        }
        assertThat(redis.opsForSet().members(RedisKeys.CAMPAIGN_INDEX)).isEmpty();
    }

    @Test
    void campaignSettingsOutsideTheirLimitsAreRefused() throws Exception {
        expectInvalid(upsert("{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":-1,\"pacing\":\"ASAP\"}"), "dailyBudgetMicros");
        expectInvalid(upsert("{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":10000000000001,\"pacing\":\"ASAP\"}"), "dailyBudgetMicros");
        expectInvalid(upsert("{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":1,\"pacing\":\"ASAP\",\"negativeKeywords\":[\"free\\u0007\"]}"),
                "negativeKeywords[0]");
        String tooMany = Collections.nCopies(201, "\"k\"").stream().collect(Collectors.joining(","));
        expectInvalid(upsert("{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":1,\"pacing\":\"ASAP\",\"negativeKeywords\":[" + tooMany + "]}"),
                "negativeKeywords");
        upsert("{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":10000000000000,\"pacing\":\"ASAP\"}").andExpect(status().isOk());
    }

    @Test
    void invalidQueryParametersAreRefusedWithoutEchoingThem() throws Exception {
        String response = mvc.perform(asAdmin(post("/v1/decisions/r-1/replay")).param("currentState", "<b>yes</b>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parameter 'currentState' has an invalid value."))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("<b>");
    }

    private ResultActions decide(String body) throws Exception {
        return mvc.perform(asClient(post("/v1/eligibility")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions upsert(String body) throws Exception {
        return mvc.perform(asAdmin(put("/admin/campaigns/c-limits")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static ResultActions expectInvalid(ResultActions result, String field) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request is not valid."))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }
}
