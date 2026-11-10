package io.github.gargshishirr.adgate.eligibility;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;
import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Candidate;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Device;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityResponse;
import io.github.gargshishirr.adgate.eligibility.domain.Placement;
import io.github.gargshishirr.adgate.eligibility.engine.EligibilityEngine;
import io.github.gargshishirr.adgate.eligibility.engine.ServingState;
import io.github.gargshishirr.adgate.eligibility.state.ServingStateLoader;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Exercises the serving path (state read, engine, JSON) before the instance reports ready, so the first real
 * requests do not pay for JIT compilation and cold connections. Spring Boot only reports readiness
 * (/actuator/health/readiness) once application runners have finished.
 */
@Component
class WarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarmUpRunner.class);
    private static final int MAX_ITERATIONS = 5_000;

    private final ServingStateLoader loader;
    private final EligibilityEngine engine;
    private final ObjectMapper mapper;
    private final CircuitBreaker redisBreaker;
    private final Duration duration;
    private final Environment environment;
    private final HttpClient http = HttpClient.newHttpClient();

    WarmUpRunner(ServingStateLoader loader, EligibilityEngine engine, ObjectMapper mapper, CircuitBreaker redisBreaker,
                 AdgateProperties properties, Environment environment) {
        this.loader = loader;
        this.engine = engine;
        this.mapper = mapper;
        this.redisBreaker = redisBreaker;
        this.duration = properties.warmUp();
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        EligibilityRequest probe = new EligibilityRequest("warm-up", Placement.SEARCH_RESULTS, "warm up",
                new Device("warm-up", null), List.of(new Candidate("warm-up", "warm-up")));
        HttpRequest rejectedRequest = rejectedHttpRequest();
        long deadline = System.nanoTime() + duration.toNanos();
        int iterations = 0;
        while (System.nanoTime() < deadline && iterations < MAX_ITERATIONS) {
            ServingState state = loader.load(probe, true);
            List<Decision> decisions = engine.evaluate(probe, state);
            mapper.writeValueAsString(new EligibilityResponse(probe.requestId(), state.nowMillis(), 0, decisions));
            if (rejectedRequest != null && iterations % 5 == 0) {
                sendQuietly(rejectedRequest);
            }
            iterations++;
        }
        // Slow calls while cold must not count against the breaker once real traffic starts.
        redisBreaker.reset();
        log.info("Warm-up finished after {} iterations; ready for traffic", iterations);
    }

    /**
     * An invalid request (no candidates) exercises Tomcat, JSON binding and validation end to end, but is rejected
     * with 400 before it can create a decision, a metric or a Kafka record.
     */
    private HttpRequest rejectedHttpRequest() {
        Integer port = environment.getProperty("local.server.port", Integer.class);
        if (port == null) {
            return null;
        }
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/eligibility"))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"requestId\":\"warm-up\",\"placement\":\"SEARCH_RESULTS\",\"device\":{\"id\":\"warm-up\"},\"candidates\":[]}"))
                .build();
    }

    private void sendQuietly(HttpRequest request) throws InterruptedException {
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.debug("HTTP warm-up request failed: {}", e.toString());
        }
    }
}
