package dev.fleetpulse.api.mqtt.credentials;

import java.time.Instant;

// Task 3.1: design.md's documented GET /api/mqtt/credentials response shape
// -- { username, password, wsUrl, expiresAt }.
public record MqttCredentialsResponse(String username, String password, String wsUrl, Instant expiresAt) {
}
