package dev.fleetpulse.api.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.mqtt")
@Validated
public record FleetpulseMqttProperties(@NotBlank String brokerUrl) {
}
