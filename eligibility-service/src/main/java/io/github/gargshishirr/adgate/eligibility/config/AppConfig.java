package io.github.gargshishirr.adgate.eligibility.config;

import java.time.Clock;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.gargshishirr.adgate.common.Topics;
import io.github.gargshishirr.adgate.eligibility.engine.EligibilityEngine;
import io.github.gargshishirr.adgate.eligibility.engine.EngineSettings;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Configuration
@EnableScheduling
class AppConfig {

    /** Name of the circuit breaker that guards every Redis call on the serving path. */
    static final String REDIS_BREAKER = "redis";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EligibilityEngine eligibilityEngine(EngineSettings settings) {
        return new EligibilityEngine(settings);
    }

    @Bean
    CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(REDIS_BREAKER);
    }

    @Bean
    @ConditionalOnProperty(name = "adgate.decisions.publish", havingValue = "true", matchIfMissing = true)
    NewTopic eligibilityDecisionsTopic() {
        return TopicBuilder.name(Topics.ELIGIBILITY_DECISIONS).partitions(3).replicas(1).build();
    }
}
