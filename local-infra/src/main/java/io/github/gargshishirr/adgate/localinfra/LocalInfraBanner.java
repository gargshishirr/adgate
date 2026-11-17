package io.github.gargshishirr.adgate.localinfra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.stereotype.Component;

@Component
class LocalInfraBanner {

    private static final Logger log = LoggerFactory.getLogger(LocalInfraBanner.class);

    private final EmbeddedKafkaBroker kafka;
    private final LocalInfraProperties properties;

    LocalInfraBanner(EmbeddedKafkaBroker kafka, LocalInfraProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent event) {
        String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port");
        log.warn("DEV ONLY: in-memory Kafka and Redis without passwords, plus a chaos switch. "
                + "Everything listens on 127.0.0.1 only. Never run this on a shared or public server.");
        log.info("Local infrastructure ready: Kafka {} | Redis 127.0.0.1:{} | chaos switch (POST only) http://127.0.0.1:{}/chaos/redis/{down|slow|normal}",
                kafka.getBrokersAsString(), properties.redisPort(), port);
    }
}
