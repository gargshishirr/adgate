package io.github.gargshishirr.adgate.eligibility.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.eligibility.security.ApiKeyFilter;

/** Shared helpers for tests that drive the HTTP API against an in-process Redis server. */
final class ApiTestSupport {

    static final int SPEND_PARTITIONS = 6;
    static final String ADMIN_KEY = "test-admin-key-0123456789abcdef0123456789";
    static final String CLIENT_KEY = "test-client-key-0123456789abcdef012345678";
    /** A second client key, as during a rotation. */
    static final String NEXT_CLIENT_KEY = "test-client-key-next-0123456789abcdef0123";

    private ApiTestSupport() {
    }

    static RedisServer startRedis() {
        try {
            return RedisServer.newRedisServer().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void redisProperties(RedisServer redis, DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getBindPort);
    }

    /** Fixed keys, so tests never depend on keys from the environment or on generated ones. */
    static void apiKeyProperties(DynamicPropertyRegistry registry) {
        registry.add("adgate.security.admin-keys", () -> ADMIN_KEY);
        registry.add("adgate.security.client-keys", () -> CLIENT_KEY + "," + NEXT_CLIENT_KEY);
    }

    static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyFilter.API_KEY_HEADER, ADMIN_KEY);
    }

    static MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyFilter.API_KEY_HEADER, CLIENT_KEY);
    }

    static String eligibilityBody(String requestId, String query, String... campaignIds) {
        String candidates = Arrays.stream(campaignIds)
                .map(id -> "{\"adId\":\"ad-" + id + "\",\"campaignId\":\"" + id + "\"}")
                .collect(Collectors.joining(","));
        return """
                {"requestId":"%s","placement":"SEARCH_RESULTS","query":"%s",
                 "device":{"id":"device-%s","userAgent":"AppStore/3.0 iOS/19.1"},
                 "candidates":[%s]}
                """.formatted(requestId, query, requestId, candidates);
    }

    static void flush(StringRedisTemplate redis) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    /** Marks every spend partition as caught up "now", as a healthy spend aggregator would. */
    static void freshSpendWatermarks(StringRedisTemplate redis, long epochMillis) {
        for (int partition = 0; partition < SPEND_PARTITIONS; partition++) {
            redis.opsForValue().set(RedisKeys.watermark(partition), Long.toString(epochMillis));
        }
    }

    static void putCampaign(MockMvc mvc, String campaignId, String json) throws Exception {
        mvc.perform(asAdmin(put("/admin/campaigns/" + campaignId)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    static ResultActions requestEligibility(MockMvc mvc, String requestId, String query, String... campaignIds) throws Exception {
        return mvc.perform(asClient(post("/v1/eligibility")).contentType(MediaType.APPLICATION_JSON)
                        .content(eligibilityBody(requestId, query, campaignIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMicros", notNullValue()));
    }

    static ResultActions expectReason(ResultActions result, int candidate, String reason) throws Exception {
        return result.andExpect(jsonPath("$.decisions[" + candidate + "].reason").value(reason));
    }

    static ResultActions expectDetailContains(ResultActions result, int candidate, String text) throws Exception {
        return result.andExpect(jsonPath("$.decisions[" + candidate + "].detail", containsString(text)));
    }

    static ResultActions getJson(MockMvc mvc, String path) throws Exception {
        return mvc.perform(asAdmin(get(path)));
    }
}
