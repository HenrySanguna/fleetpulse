package dev.fleetpulse.processor.presence;

// Wire shape of the presence JSON payload published on
// fleet/{orgId}/vehicle/{vehicleId}/status, both as the device's registered
// Last Will and Testament and as its own "I am online" announcement after
// connecting (see PresenceMqttConfig's class Javadoc for the full contract,
// task 5.1). Only `online` is part of the contract: there is no recordedAt
// field to guard ordering with, unlike telemetry -- MQTT retained-message
// semantics already make the latest publish to this topic win.
public record PresencePayload(Boolean online) {
}
