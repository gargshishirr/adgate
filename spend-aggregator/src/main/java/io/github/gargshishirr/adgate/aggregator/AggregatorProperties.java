package io.github.gargshishirr.adgate.aggregator;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param spendPartitions        partition count of the spend topic; spend for one campaign always lands on one partition
 * @param replicationFactor      replication factor used when the aggregator creates the spend topic
 * @param dedupeTtl              how long applied event ids are remembered; must exceed the longest possible redelivery delay
 * @param spendTtl               how long daily spend counters are kept after their last update
 * @param watermarkCheckInterval minimum time between broker lag checks, per consumer thread
 * @param endOffsetsTimeout      bound on the broker call that proves a partition is caught up
 */
@ConfigurationProperties("adgate.aggregator")
public record AggregatorProperties(
        @DefaultValue("6") int spendPartitions,
        @DefaultValue("1") int replicationFactor,
        @DefaultValue("2d") Duration dedupeTtl,
        @DefaultValue("3d") Duration spendTtl,
        @DefaultValue("500ms") Duration watermarkCheckInterval,
        @DefaultValue("2s") Duration endOffsetsTimeout) {
}
