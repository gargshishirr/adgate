package io.github.gargshishirr.adgate.aggregator;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import io.github.gargshishirr.adgate.common.Topics;

@Configuration
class KafkaConfig {

    /**
     * Broker-assigned (LogAppendTime) timestamps give every partition one monotonic clock, so a watermark
     * derived from record timestamps never depends on producer clock skew.
     */
    @Bean
    NewTopic spendEventsTopic(AggregatorProperties properties) {
        return TopicBuilder.name(Topics.SPEND_EVENTS)
                .partitions(properties.spendPartitions())
                .replicas(properties.replicationFactor())
                .config(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG, "LogAppendTime")
                .build();
    }

    /**
     * Spend must never be dropped: if the ledger is unreachable, retry the batch until it recovers.
     * Retrying is safe because the ledger is idempotent, and while we retry the watermarks stop moving,
     * so the eligibility service fails closed instead of serving against an outdated budget.
     */
    @Bean
    DefaultErrorHandler spendErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
