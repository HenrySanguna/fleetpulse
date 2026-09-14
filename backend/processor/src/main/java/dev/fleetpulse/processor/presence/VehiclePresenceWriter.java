package dev.fleetpulse.processor.presence;

import java.util.UUID;

// Small port PresenceMessageListener writes through, mirroring
// TelemetryPositionWriter's own reason to exist: lets the listener be tested
// (TelemetryMqttConsumerTest's pattern) against a no-op fake instead of a
// real database. JdbcVehiclePresenceWriter is the only production
// implementation.
public interface VehiclePresenceWriter {

    void updateOnlineStatus(UUID vehicleId, boolean online);
}
