package dev.fleetpulse.api.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.security")
@Validated
public record FleetpulseSessionProperties(@Positive @DefaultValue("25") int maxSessionsPerUser) {
}
