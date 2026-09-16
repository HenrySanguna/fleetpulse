package dev.fleetpulse.processor.alerts;

import java.util.UUID;

// Composite dedup key AlertRuleDispatcher/JdbcAlertSilenceStateStore share
// for one in-memory (per-batch) or persisted (alert_silence_state, V12)
// AlertSilenceState. context is null for the vehicle-level types this work
// unit adds (speeding, excessive_idle); a future per-context alert type
// would populate it, the same "context is the alert's sub-resource, when it
// has one" convention Alert itself already establishes.
public record AlertSilenceKey(UUID vehicleId, AlertType alertType, UUID context) {
}
