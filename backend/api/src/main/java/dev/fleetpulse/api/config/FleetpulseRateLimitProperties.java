package dev.fleetpulse.api.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Per-client-IP request budgets (per minute) enforced by RateLimitFilter. The
// bucket cache is bounded and expires idle clients so a spray of source
// addresses cannot grow memory without limit.
@ConfigurationProperties(prefix = "fleetpulse.security.rate-limit")
@Validated
public record FleetpulseRateLimitProperties(
        @Positive @DefaultValue("5") int loginPerMinute,
        @Positive @DefaultValue("6") int mqttCredentialsPerMinute,
        @Positive @DefaultValue("120") int apiPerMinute,
        @Positive @DefaultValue("10000") int maxTrackedClients,
        @NotNull @DefaultValue("10m") Duration clientIdleExpiry) {
}
