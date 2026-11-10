package io.github.gargshishirr.adgate.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpendPartitionerTest {

    @ParameterizedTest
    @ValueSource(strings = {"c-1", "c-2", "campaign-42", "ünïcödé", "a"})
    void matchesKafkaDefaultPartitionerForKeyedRecords(String campaignId) {
        for (int partitions : new int[] {1, 3, 6, 12}) {
            int expected = BuiltInPartitioner.partitionForKey(campaignId.getBytes(StandardCharsets.UTF_8), partitions);
            assertThat(SpendPartitioner.partitionFor(campaignId, partitions)).isEqualTo(expected);
        }
    }

    @Test
    void rejectsNonPositivePartitionCount() {
        assertThatThrownBy(() -> SpendPartitioner.partitionFor("c-1", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
