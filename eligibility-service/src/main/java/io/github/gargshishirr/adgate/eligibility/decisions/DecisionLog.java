package io.github.gargshishirr.adgate.eligibility.decisions;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;

/**
 * Bounded in-memory log of recent decisions that backs the explain and replay endpoints. The durable copy of
 * every decision is the Kafka topic; this log is for fast on-call debugging on the instance that served it.
 */
@Component
public class DecisionLog {

    private final Cache<String, DecisionRecord> recent;

    public DecisionLog(AdgateProperties properties) {
        this.recent = Caffeine.newBuilder()
                .maximumSize(properties.decisions().logSize())
                .expireAfterWrite(properties.decisions().logTtl())
                .build();
    }

    public void put(DecisionRecord record) {
        recent.put(record.request().requestId(), record);
    }

    public Optional<DecisionRecord> get(String requestId) {
        return Optional.ofNullable(recent.getIfPresent(requestId));
    }
}
