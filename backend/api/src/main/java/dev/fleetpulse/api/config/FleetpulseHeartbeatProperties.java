package dev.fleetpulse.api.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "fleetpulse.mqtt.heartbeat")
@Validated
public record FleetpulseHeartbeatProperties(
    @NotBlank @DefaultValue("fleetpulse/processor/heartbeat") String topic,
    // 3x processor's 30s publish interval: absorbs scheduling jitter and one
    // missed publish without flipping health to DOWN on every minor delay.
    @NotNull @DefaultValue("90s") Duration stalenessThreshold
) {
}
