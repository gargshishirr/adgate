package io.github.gargshishirr.adgate.common;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.utils.Utils;

/**
 * Computes which partition of the spend topic owns a campaign, using the same algorithm as Kafka's
 * default partitioner for keyed records (murmur2 of the UTF-8 key).
 *
 * <p>The eligibility service uses this to check freshness of the one partition that carries a campaign's
 * spend, so a stalled partition only fails closed the campaigns it owns instead of all traffic.
 */
public final class SpendPartitioner {

    private SpendPartitioner() {
    }

    public static int partitionFor(String campaignId, int numPartitions) {
        if (numPartitions <= 0) {
            throw new IllegalArgumentException("numPartitions must be positive");
        }
        byte[] key = campaignId.getBytes(StandardCharsets.UTF_8);
        return Utils.toPositive(Utils.murmur2(key)) % numPartitions;
    }
}
