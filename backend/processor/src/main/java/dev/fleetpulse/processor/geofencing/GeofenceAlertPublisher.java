package dev.fleetpulse.processor.geofencing;

// Port task 4.2 publishes through, kept separate from the real MQTT
// implementation (MqttGeofenceAlertPublisher) the same way
// TelemetryPositionWriter separates JdbcTelemetryPositionWriter from its
// buffer caller: tests that do not exercise geofencing
// (TelemetryBatchWriteTest, TelemetryVehicleStateGuardTest) can wire
// GeofenceRuleDispatcher against a no-op implementation instead of standing
// up a broker connection they have no use for.
@FunctionalInterface
public interface GeofenceAlertPublisher {

    void publish(GeofenceAlert alert);
}
