package io.github.gargshishirr.adgate.eligibility;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;
import io.github.gargshishirr.adgate.eligibility.decisions.DecisionLog;
import io.github.gargshishirr.adgate.eligibility.decisions.DecisionPublisher;
import io.github.gargshishirr.adgate.eligibility.decisions.DecisionRecord;
import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityResponse;
import io.github.gargshishirr.adgate.eligibility.engine.DependencyStatus;
import io.github.gargshishirr.adgate.eligibility.engine.EligibilityEngine;
import io.github.gargshishirr.adgate.eligibility.engine.ServingState;
import io.github.gargshishirr.adgate.eligibility.state.ServingStateLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class EligibilityService {

    private final ServingStateLoader loader;
    private final EligibilityEngine engine;
    private final DecisionLog decisionLog;
    private final DecisionPublisher publisher;
    private final Duration latencyBudget;
    private final MeterRegistry registry;
    private final Timer latency;

    public EligibilityService(ServingStateLoader loader, EligibilityEngine engine, DecisionLog decisionLog,
                              DecisionPublisher publisher, AdgateProperties properties, MeterRegistry registry) {
        this.loader = loader;
        this.engine = engine;
        this.decisionLog = decisionLog;
        this.publisher = publisher;
        this.latencyBudget = properties.latencyBudget();
        this.registry = registry;
        this.latency = Timer.builder("adgate.eligibility.latency")
                .description("Server-side time to decide eligibility for one ad request")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50))
                .register(registry);
    }

    public EligibilityResponse evaluate(EligibilityRequest request) {
        long start = System.nanoTime();
        ServingState state = loader.load(request, true);
        long loadNanos = System.nanoTime() - start;
        if (state.dependencyStatus() == DependencyStatus.OK && loadNanos > latencyBudget.toNanos()) {
            state = state.withDependencyStatus(DependencyStatus.DEADLINE_EXCEEDED, String.format(Locale.ROOT,
                    "reading serving state took %.1f ms (budget %d ms)", loadNanos / 1e6, latencyBudget.toMillis()));
        }
        List<Decision> decisions = engine.evaluate(request, state);
        long elapsedNanos = System.nanoTime() - start;

        latency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        for (Decision decision : decisions) {
            registry.counter("adgate.eligibility.decisions", "reason", decision.reason().name()).increment();
        }
        DecisionRecord record = new DecisionRecord(request, state, decisions, elapsedNanos / 1_000);
        decisionLog.put(record);
        publisher.offer(record);
        return new EligibilityResponse(request.requestId(), state.nowMillis(), elapsedNanos / 1_000, decisions);
    }

    public Optional<DecisionRecord> explain(String requestId) {
        return decisionLog.get(requestId);
    }

    /**
     * Re-runs the engine on the stored snapshot, which must reproduce the original decisions exactly. Optionally
     * also evaluates the same request against current state, to answer "would this serve now?".
     */
    public Optional<ReplayResult> replay(String requestId, boolean againstCurrentState) {
        return decisionLog.get(requestId).map(original -> {
            List<Decision> replayed = engine.evaluate(original.request(), original.state());
            List<Decision> current = againstCurrentState
                    ? engine.evaluate(original.request(), loader.load(original.request(), false))
                    : null;
            return new ReplayResult(requestId, replayed.equals(original.decisions()), original.decisions(), replayed, current);
        });
    }
}
