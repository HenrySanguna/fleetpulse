package dev.fleetpulse.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Task 3.1: the browser-facing WebSocket URL returned to dispatchers alongside
// their ephemeral MQTT credentials, and how long those credentials stay
// valid before ExpiredMqttCredentialPurgeTask reclaims them (task 3.3).
// design.md: a short TTL is the deliberate mitigation for dispatcher-session
// revocation not being instantaneous on the MQTT channel.
@ConfigurationProperties(prefix = "fleetpulse.mqtt.browser")
public record FleetpulseMqttBrowserCredentialProperties(String wsUrl, Duration credentialTtl) {
}
