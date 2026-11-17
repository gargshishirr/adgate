package io.github.gargshishirr.adgate.eligibility.api;

import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.ADMIN_KEY;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.CLIENT_KEY;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.NEXT_CLIENT_KEY;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.asAdmin;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.eligibilityBody;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectReason;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.freshSpendWatermarks;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.requestEligibility;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.eligibility.security.ApiKeyFilter;

/** API keys on the main port: missing, wrong and right keys, and admin versus client separation. */
@SpringBootTest(properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "adgate.latency-budget=5s",
        "spring.data.redis.timeout=5s",
        "resilience4j.circuitbreaker.instances.redis.slow-call-duration-threshold=5s"
})
@AutoConfigureMockMvc
class ApiKeyAuthIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();
    private static final String CAMPAIGN = "{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":50000000,\"pacing\":\"ASAP\"}";

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
        freshSpendWatermarks(redis, System.currentTimeMillis());
    }

    @Test
    void aMissingKeyIsRefusedWithProblemJson() throws Exception {
        mvc.perform(decision("r-no-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"adgate\""))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail", containsString(ApiKeyFilter.API_KEY_HEADER)))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());

        mvc.perform(put("/admin/campaigns/c-1").contentType(MediaType.APPLICATION_JSON).content(CAMPAIGN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/campaigns/c-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void noBodyIsReadBeforeTheKeyIsChecked() throws Exception {
        // A form body Spring would fail to parse: refused for the missing key instead, so nothing read it first.
        mvc.perform(put("/admin/campaigns/c-1").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=%zz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongKeyIsRefused() throws Exception {
        mvc.perform(decision("r-wrong").header(ApiKeyFilter.API_KEY_HEADER, "not-a-real-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The API key is not valid."));
        mvc.perform(decision("r-almost").header(ApiKeyFilter.API_KEY_HEADER, CLIENT_KEY + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/campaigns").header("Authorization", "Bearer " + ADMIN_KEY.substring(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyConfiguredClientKeyCanCallTheDecisionApi() throws Exception {
        mvc.perform(decision("r-current").header(ApiKeyFilter.API_KEY_HEADER, CLIENT_KEY)).andExpect(status().isOk());
        mvc.perform(decision("r-next").header(ApiKeyFilter.API_KEY_HEADER, NEXT_CLIENT_KEY)).andExpect(status().isOk());
        mvc.perform(decision("r-bearer").header("Authorization", "Bearer " + CLIENT_KEY)).andExpect(status().isOk());
    }

    @Test
    void clientKeysCannotUseAdminOrDebugEndpoints() throws Exception {
        requestEligibility(mvc, "r-seen", "photo editor", "c-1");

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                put("/admin/campaigns/c-1").contentType(MediaType.APPLICATION_JSON).content(CAMPAIGN),
                get("/admin/campaigns"),
                get("/admin/campaigns/c-1"),
                delete("/admin/campaigns/c-1"),
                get("/v1/decisions/r-seen"),
                post("/v1/decisions/r-seen/replay")}) {
            mvc.perform(request.header(ApiKeyFilter.API_KEY_HEADER, CLIENT_KEY))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("This endpoint needs the admin key."));
        }
        mvc.perform(asAdmin(get("/admin/campaigns/c-1"))).andExpect(status().isNotFound());
    }

    @Test
    void theAdminKeyCannotCallTheDecisionApi() throws Exception {
        mvc.perform(decision("r-admin").header(ApiKeyFilter.API_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This endpoint needs a client key."));
    }

    @Test
    void theAdminKeyCreatesReadsAndDeletesCampaigns() throws Exception {
        mvc.perform(asAdmin(put("/admin/campaigns/c-1")).contentType(MediaType.APPLICATION_JSON).content(CAMPAIGN))
                .andExpect(status().isOk());
        mvc.perform(asAdmin(get("/admin/campaigns/c-1"))).andExpect(status().isOk());
        expectReason(requestEligibility(mvc, "r-before-delete", "photo editor", "c-1"), 0, "ELIGIBLE");

        mvc.perform(asAdmin(delete("/admin/campaigns/c-1"))).andExpect(status().isNoContent());
        mvc.perform(asAdmin(delete("/admin/campaigns/c-1"))).andExpect(status().isNotFound());
        mvc.perform(asAdmin(get("/admin/campaigns"))).andExpect(content().json("[]"));
        expectReason(requestEligibility(mvc, "r-after-delete", "photo editor", "c-1"), 0, "CAMPAIGN_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/eligibility;jsessionid=x", "/v1//eligibility", "/v1/./eligibility",
            "/v1/eligibility/../../admin/campaigns", "/admin/campaigns/%2e%2e", "/admin/campaigns/c%2D1"})
    void pathsThatCouldBeRoutedDifferentlyFromHowTheyWereCheckedAreRefused(String path) throws Exception {
        mvc.perform(post(URI.create(path)).header(ApiKeyFilter.API_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(eligibilityBody("r-path", "q", "c-1")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request path is not valid."));
    }

    private static MockHttpServletRequestBuilder decision(String requestId) {
        return post("/v1/eligibility").contentType(MediaType.APPLICATION_JSON)
                .content(eligibilityBody(requestId, "photo editor", "c-1"));
    }
}
