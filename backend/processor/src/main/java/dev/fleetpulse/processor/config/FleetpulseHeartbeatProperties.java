package dev.fleetpulse.processor.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.mqtt.heartbeat")
@Validated
public record FleetpulseHeartbeatProperties(
    @NotBlank @DefaultValue("fleetpulse/processor/heartbeat") String topic
) {
}
