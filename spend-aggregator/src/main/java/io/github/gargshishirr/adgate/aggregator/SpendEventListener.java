package io.github.gargshishirr.adgate.aggregator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.common.SpendEvent;
import io.github.gargshishirr.adgate.common.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class SpendEventListener {

    static final String LISTENER_ID = "spend-aggregator";

    private static final Logger log = LoggerFactory.getLogger(SpendEventListener.class);

    private final ObjectMapper mapper;
    private final SpendLedger ledger;
    private final WatermarkTracker watermarks;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter invalid;
    private final Timer batchLatency;

    public SpendEventListener(ObjectMapper mapper, SpendLedger ledger, WatermarkTracker watermarks, MeterRegistry registry) {
        this.mapper = mapper;
        this.ledger = ledger;
        this.watermarks = watermarks;
        this.applied = eventCounter(registry, "applied");
        this.duplicates = eventCounter(registry, "duplicate");
        this.invalid = eventCounter(registry, "invalid");
        this.batchLatency = Timer.builder("adgate.spend.batch.latency")
                .description("Time to parse a Kafka batch and apply it to the ledger")
                .publishPercentileHistogram()
                .register(registry);
    }

    @KafkaListener(id = LISTENER_ID, topics = Topics.SPEND_EVENTS, batch = "true")
    public void onBatch(List<ConsumerRecord<String, String>> records, Consumer<?, ?> consumer) {
        long start = System.nanoTime();
        List<SpendEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            SpendEvent event = parse(record);
            if (event == null) {
                invalid.increment();
            } else {
                events.add(event);
            }
        }
        long newlyApplied = ledger.apply(events);
        applied.increment(newlyApplied);
        duplicates.increment(events.size() - newlyApplied);

        watermarks.recordApplied(records);
        watermarks.maybeAdvance(consumer);
        batchLatency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    /** Idle containers still advance watermarks, so freshness does not depend on spend traffic. */
    @EventListener
    public void onIdle(ListenerContainerIdleEvent event) {
        if (event.getListenerId().startsWith(LISTENER_ID)) {
            watermarks.maybeAdvance(event.getConsumer());
        }
    }

    private SpendEvent parse(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            SpendEvent event = mapper.readValue(record.value(), SpendEvent.class);
            if (event.isValid()) {
                return event;
            }
            log.warn("Skipping invalid spend event at {}-{}@{}", record.topic(), record.partition(), record.offset());
        } catch (JsonProcessingException e) {
            log.warn("Skipping malformed spend event at {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
        return null;
    }

    private static Counter eventCounter(MeterRegistry registry, String result) {
        return Counter.builder("adgate.spend.events")
                .description("Spend events by outcome")
                .tag("result", result)
                .register(registry);
    }
}
