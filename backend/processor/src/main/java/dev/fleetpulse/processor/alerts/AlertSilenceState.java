package dev.fleetpulse.processor.alerts;

import java.time.Instant;

// Persisted per (vehicle, alert type, context) by JdbcAlertSilenceStateStore
// -- see AlertSilenceEngine for the decision this feeds. active = true means
// the vehicle is CURRENTLY in the alerting condition (has not yet resolved
// back to normal); lastAlertAt is when an alert was last actually emitted
// for this key (null before the first ever alert).
public record AlertSilenceState(boolean active, Instant lastAlertAt) {

    public static final AlertSilenceState NONE = new AlertSilenceState(false, null);
}
