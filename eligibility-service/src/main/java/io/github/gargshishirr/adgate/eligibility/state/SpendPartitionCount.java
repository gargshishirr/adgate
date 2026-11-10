package io.github.gargshishirr.adgate.eligibility.state;

import java.util.Map;

import org.apache.kafka.clients.admin.TopicDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.common.Topics;
import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;

/**
 * Partition count of the spend topic, needed to map a campaign to the partition whose watermark proves its spend
 * is fresh. Discovered with the admin API off the serving path; the configured value is used until then.
 * The admin API is used instead of producer metadata because a metadata lookup could auto-create the topic.
 */
@Component
public class SpendPartitionCount {

    private static final Logger log = LoggerFactory.getLogger(SpendPartitionCount.class);

    private final KafkaAdmin kafkaAdmin;
    private final boolean discover;
    private volatile int partitions;

    public SpendPartitionCount(KafkaAdmin kafkaAdmin, AdgateProperties properties) {
        this.kafkaAdmin = kafkaAdmin;
        this.discover = properties.spend().discoverPartitions();
        this.partitions = properties.spend().partitions();
    }

    public int current() {
        return partitions;
    }

    @Scheduled(initialDelay = 1_000, fixedDelay = 30_000)
    void refresh() {
        if (!discover) {
            return;
        }
        try {
            Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(Topics.SPEND_EVENTS);
            TopicDescription description = topics.get(Topics.SPEND_EVENTS);
            int discovered = description == null ? 0 : description.partitions().size();
            if (discovered > 0 && discovered != partitions) {
                log.info("Spend topic has {} partitions (was {})", discovered, partitions);
                partitions = discovered;
            }
        } catch (RuntimeException e) {
            log.debug("Could not describe {} yet: {}", Topics.SPEND_EVENTS, e.toString());
        }
    }
}
