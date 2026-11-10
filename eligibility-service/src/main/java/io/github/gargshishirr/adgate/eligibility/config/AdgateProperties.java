package io.github.gargshishirr.adgate.eligibility.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param latencyBudget time allowed to assemble serving state; past it the request is declined (fail closed)
 * @param configRefresh how often every cached campaign configuration is reloaded in the background
 * @param warmUp        how long to exercise the serving path at startup before reporting ready (0 disables)
 */
@ConfigurationProperties("adgate")
public record AdgateProperties(
        @DefaultValue("50ms") Duration latencyBudget,
        @DefaultValue("2s") Duration configRefresh,
        @DefaultValue("3s") Duration warmUp,
        @DefaultValue Spend spend,
        @DefaultValue Decisions decisions) {

    /**
     * @param partitions         expected partition count of the spend topic, used until it is discovered from Kafka
     * @param discoverPartitions whether to look up the real partition count from the Kafka cluster
     */
    public record Spend(@DefaultValue("6") int partitions, @DefaultValue("true") boolean discoverPartitions) {
    }

    /**
     * @param publish       publish every decision to Kafka for funnel analytics and offline replay
     * @param queueCapacity decisions buffered for publishing; when full, new ones are dropped (and counted), never blocking serving
     * @param logSize       recent decisions kept in memory for the explain and replay endpoints
     * @param logTtl        how long a decision stays explainable
     */
    public record Decisions(
            @DefaultValue("true") boolean publish,
            @DefaultValue("10000") int queueCapacity,
            @DefaultValue("50000") long logSize,
            @DefaultValue("30m") Duration logTtl) {
    }
}
