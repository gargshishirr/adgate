package io.github.gargshishirr.adgate.aggregator;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.resource.Delay;

@Configuration
class RedisConfig {

    /** Caps Lettuce's reconnect backoff (30s by default) so the ledger resumes within ~1s once Redis is back. */
    @Bean
    ClientResourcesBuilderCustomizer boundedRedisReconnectBackoff() {
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(10), Duration.ofSeconds(1), 2, TimeUnit.MILLISECONDS));
    }
}
