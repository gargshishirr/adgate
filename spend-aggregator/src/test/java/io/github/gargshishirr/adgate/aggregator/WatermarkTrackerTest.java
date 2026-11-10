package io.github.gargshishirr.adgate.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.gargshishirr.adgate.common.Topics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WatermarkTrackerTest {

    private static final TopicPartition P0 = new TopicPartition(Topics.SPEND_EVENTS, 0);
    private static final TopicPartition P1 = new TopicPartition(Topics.SPEND_EVENTS, 1);
    private static final long NOW = Instant.parse("2026-10-31T10:00:00Z").toEpochMilli();

    private final Map<Integer, Long> published = new HashMap<>();
    private final WatermarkStore store = new WatermarkStore(null) {
        @Override
        public void advance(Map<Integer, Long> watermarksByPartition) {
            published.putAll(watermarksByPartition);
        }
    };
    private final MutableClock clock = new MutableClock(NOW);
    private final MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

    @BeforeEach
    void assignPartitions() {
        consumer.assign(List.of(P0, P1));
        consumer.seek(P0, 10);
        consumer.seek(P1, 4);
    }

    @Test
    void caughtUpPartitionAdvancesToNowAndLaggingPartitionToLastAppliedTimestamp() {
        consumer.updateEndOffsets(Map.of(P0, 10L, P1, 9L));
        WatermarkTracker tracker = tracker(Duration.ZERO);

        tracker.recordApplied(List.of(record(P1, 3, NOW - 5_000)));
        tracker.maybeAdvance(consumer);

        assertThat(published).containsEntry(0, NOW).containsEntry(1, NOW - 5_000);
    }

    @Test
    void laggingPartitionWithNothingAppliedDoesNotAdvance() {
        consumer.updateEndOffsets(Map.of(P0, 10L, P1, 9L));

        tracker(Duration.ZERO).maybeAdvance(consumer);

        assertThat(published).containsOnlyKeys(0);
    }

    @Test
    void lagChecksAreRateLimited() {
        consumer.updateEndOffsets(Map.of(P0, 10L, P1, 4L));
        WatermarkTracker tracker = tracker(Duration.ofSeconds(1));

        tracker.maybeAdvance(consumer);
        clock.advance(Duration.ofMillis(400));
        tracker.maybeAdvance(consumer);
        assertThat(published).containsEntry(0, NOW);

        clock.advance(Duration.ofMillis(600));
        tracker.maybeAdvance(consumer);
        assertThat(published).containsEntry(0, NOW + 1_000);
    }

    private WatermarkTracker tracker(Duration checkInterval) {
        AggregatorProperties properties = new AggregatorProperties(6, 1, Duration.ofDays(2), Duration.ofDays(3),
                checkInterval, Duration.ofSeconds(1));
        return new WatermarkTracker(store, properties, clock, new SimpleMeterRegistry());
    }

    private static ConsumerRecord<String, String> record(TopicPartition partition, long offset, long timestamp) {
        return new ConsumerRecord<>(partition.topic(), partition.partition(), offset, timestamp,
                TimestampType.LOG_APPEND_TIME, 0, 0, "c-1", "{}", new RecordHeaders(), Optional.empty());
    }

    private static final class MutableClock extends Clock {

        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
