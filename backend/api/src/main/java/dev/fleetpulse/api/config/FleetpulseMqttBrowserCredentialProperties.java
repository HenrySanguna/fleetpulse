package dev.fleetpulse.api.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Task 3.1: the browser-facing WebSocket URL returned to dispatchers alongside
// their ephemeral MQTT credentials, and how long those credentials stay
// valid before ExpiredMqttCredentialPurgeTask reclaims them (task 3.3).
// design.md: a short TTL is the deliberate mitigation for dispatcher-session
// revocation not being instantaneous on the MQTT channel.
@ConfigurationProperties(prefix = "fleetpulse.mqtt.browser")
@Validated
public record FleetpulseMqttBrowserCredentialProperties(
        String wsUrl,
        Duration credentialTtl,
        @Positive @DefaultValue("50") int maxActivePerUser) {
}
