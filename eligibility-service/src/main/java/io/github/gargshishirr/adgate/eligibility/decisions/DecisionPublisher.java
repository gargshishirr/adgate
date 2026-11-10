package io.github.gargshishirr.adgate.eligibility.decisions;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.common.Topics;
import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes every decision to Kafka for funnel analytics and offline replay without ever blocking serving:
 * the request thread only offers to a bounded queue, and a background virtual thread does the Kafka I/O.
 * When the queue is full the record is dropped and counted; analytics loses a record, serving loses nothing.
 */
@Component
public class DecisionPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DecisionPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final BlockingQueue<DecisionRecord> queue;
    private final Counter published;
    private final Counter failed;
    private final Counter dropped;

    private volatile boolean running;
    private Thread worker;

    public DecisionPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, AdgateProperties properties, MeterRegistry registry) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.enabled = properties.decisions().publish();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.decisions().queueCapacity()));
        this.published = outcome(registry, "published");
        this.failed = outcome(registry, "failed");
        this.dropped = outcome(registry, "dropped");
        registry.gauge("adgate.decisions.queue.size", queue, BlockingQueue::size);
    }

    /** Never blocks. Returns false if publishing is disabled or the queue is full. */
    public boolean offer(DecisionRecord record) {
        if (!enabled) {
            return false;
        }
        boolean accepted = queue.offer(record);
        if (!accepted) {
            dropped.increment();
        }
        return accepted;
    }

    @Override
    public void start() {
        running = true;
        if (enabled) {
            worker = Thread.ofVirtual().name("decision-publisher").start(this::drain);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void drain() {
        while (running) {
            DecisionRecord record;
            try {
                record = queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (record != null) {
                publish(record);
            }
        }
    }

    private void publish(DecisionRecord record) {
        try {
            String json = mapper.writeValueAsString(record);
            kafka.send(Topics.ELIGIBILITY_DECISIONS, record.request().requestId(), json)
                    .whenComplete((result, error) -> (error == null ? published : failed).increment());
        } catch (Exception e) {
            failed.increment();
            log.debug("Could not publish decision {}: {}", record.request().requestId(), e.toString());
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("adgate.decisions.events")
                .description("Decision records sent to Kafka, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
