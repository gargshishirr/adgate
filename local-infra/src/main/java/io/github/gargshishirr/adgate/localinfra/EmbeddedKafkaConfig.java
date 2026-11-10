package io.github.gargshishirr.adgate.localinfra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

@Configuration
class EmbeddedKafkaConfig {

    /**
     * Spring starts and stops the broker through its InitializingBean/DisposableBean lifecycle. Topic
     * auto-creation is off, as in production, so topics only exist with the partition count and config
     * that the services declare.
     *
     * <p>The client listener is pinned through the {@code listeners} config rather than {@code kafkaPorts(..)},
     * because the embedded KRaft broker ignores {@code kafkaPorts} and would otherwise pick a random port.
     */
    @Bean
    EmbeddedKafkaBroker embeddedKafka(LocalInfraProperties properties) {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.brokerProperty("listeners", "EXTERNAL://localhost:" + properties.kafkaPort() + ",CONTROLLER://localhost:0");
        broker.brokerProperty("auto.create.topics.enable", "false");
        return broker;
    }
}
