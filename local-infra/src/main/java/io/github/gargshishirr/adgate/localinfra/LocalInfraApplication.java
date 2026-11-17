package io.github.gargshishirr.adgate.localinfra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Profiles;

/**
 * Docker-free infrastructure for local development: a single-node KRaft Kafka broker and a Redis-compatible
 * server in one JVM. Not for production; it exists so the whole system runs with nothing but a JDK. Everything it
 * opens listens on 127.0.0.1 only, and nothing has a password.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalInfraApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(LocalInfraApplication.class);
        application.addListeners(refuseProdProfile());
        application.run(args);
    }

    /** A production-looking environment is a sign this is running somewhere it should not. */
    static ApplicationListener<ApplicationEnvironmentPreparedEvent> refuseProdProfile() {
        return event -> {
            if (event.getEnvironment().acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        "local-infra is for local development only and must not run with the prod profile");
            }
        };
    }
}
