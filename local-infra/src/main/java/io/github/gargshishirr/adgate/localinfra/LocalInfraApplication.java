package io.github.gargshishirr.adgate.localinfra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Docker-free infrastructure for local development: a single-node KRaft Kafka broker and a Redis-compatible
 * server in one JVM. Not for production; it exists so the whole system runs with nothing but a JDK.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalInfraApplication {

    private static final Logger log = LoggerFactory.getLogger(LocalInfraApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(LocalInfraApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent event) {
        EmbeddedKafkaBroker kafka = event.getApplicationContext().getBean(EmbeddedKafkaBroker.class);
        LocalInfraProperties properties = event.getApplicationContext().getBean(LocalInfraProperties.class);
        String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port");
        log.info("Local infrastructure ready: Kafka {} | Redis localhost:{} | chaos switch http://localhost:{}/chaos/redis",
                kafka.getBrokersAsString(), properties.redisPort(), port);
    }
}
