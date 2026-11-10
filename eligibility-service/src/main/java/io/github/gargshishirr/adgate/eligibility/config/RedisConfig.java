package io.github.gargshishirr.adgate.eligibility.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.resource.Delay;

@Configuration
class RedisConfig {

    /**
     * Lettuce backs off exponentially up to 30s between reconnect attempts by default, so after a long Redis
     * outage the service could keep failing closed for many seconds after Redis is back. Capping the backoff
     * at 1s bounds recovery time.
     */
    @Bean
    ClientResourcesBuilderCustomizer boundedRedisReconnectBackoff() {
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(10), Duration.ofSeconds(1), 2, TimeUnit.MILLISECONDS));
    }
}
