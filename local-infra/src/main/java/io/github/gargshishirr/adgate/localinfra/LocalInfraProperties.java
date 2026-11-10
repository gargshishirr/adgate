package io.github.gargshishirr.adgate.localinfra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("local-infra")
public record LocalInfraProperties(@DefaultValue("9092") int kafkaPort, @DefaultValue("6379") int redisPort) {
}
