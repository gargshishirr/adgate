package io.github.gargshishirr.adgate.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrafficSimApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(TrafficSimApplication.class, args)));
    }
}
