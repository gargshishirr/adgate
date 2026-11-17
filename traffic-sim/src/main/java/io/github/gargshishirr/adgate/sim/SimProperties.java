package io.github.gargshishirr.adgate.sim;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param clientKey            ADGATE_CLIENT_KEY, sent with every decision request (the first key if it is a list)
 * @param adminKey             ADGATE_ADMIN_KEY, used to seed campaigns and print the summary (the first key if it is a list)
 * @param winRate              chance that an eligible request wins its auction and produces a spend event
 * @param duplicateSpendShare  share of spend events published twice, to exercise the ledger's idempotency
 * @param unknownCampaignShare share of requests that include a campaign with no configuration
 * @param botShare             share of requests from automated traffic (a bot user agent or one hyperactive device)
 */
@ConfigurationProperties("sim")
public record SimProperties(
        @DefaultValue("http://localhost:8081") URI eligibilityUrl,
        String clientKey,
        String adminKey,
        @DefaultValue("150") int requestsPerSecond,
        @DefaultValue("90s") Duration duration,
        @DefaultValue("5s") Duration reportInterval,
        @DefaultValue("true") boolean seedCampaigns,
        @DefaultValue("8") int campaigns,
        @DefaultValue("3") int candidatesPerRequest,
        @DefaultValue("500") int devices,
        @DefaultValue("0.04") double botShare,
        @DefaultValue("0.35") double winRate,
        @DefaultValue("40000") long minCostMicros,
        @DefaultValue("250000") long maxCostMicros,
        @DefaultValue("0.02") double duplicateSpendShare,
        @DefaultValue("0.02") double unknownCampaignShare) {

    /** The first key of a comma-separated list, or null if none is set. */
    static String firstKey(String keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys.split(",")) {
            if (!key.isBlank()) {
                return key.strip();
            }
        }
        return null;
    }
}
