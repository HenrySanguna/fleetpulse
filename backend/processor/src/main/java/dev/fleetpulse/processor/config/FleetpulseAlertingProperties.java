package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Tasks 3.2/3.3: every tuning value AlertRuleDispatcher/AlertSilenceEngine
// need. Global, not per-organization -- unlike organizations.trip_stop_threshold_secs
// (V10), which spec.md explicitly requires to vary per tenant, neither
// design.md nor spec.md asks a speed limit or an idle threshold to vary per
// organization, so this follows FleetpulseGeofencingProperties/
// FleetpulseMotionDetectionProperties/FleetpulseEtaProperties/
// FleetpulseTripsProperties' own precedent: a single global
// @ConfigurationProperties record, not a DB column. Documented deviation
// (design.md/spec.md state neither an exact speed limit nor an exact idle
// threshold), per this project's "note deviations, don't silently freelance"
// convention.
@ConfigurationProperties(prefix = "fleetpulse.alerting")
public record FleetpulseAlertingProperties(
    // A single fleet-wide highway-class limit -- most fleet vehicles
    // (vans/trucks) operate under this regardless of road type; a genuinely
    // per-road-segment limit would need real road-network data this MVP's
    // ETA calculation explicitly does not have either (design.md, "ETA:
    // honestidad sobre lo que es").
    @DefaultValue("100.0") double speedLimitKmh,
    // "Ralenti excesivo": how long a vehicle can stay in geo-core's IDLING
    // MotionState (reusing V7's low_speed_streak_started_at, per this work
    // unit's own instruction not to invent a second idle-detection mechanism
    // -- see AlertRuleDispatcher's class comment) before it counts as
    // excessive rather than an ordinary brief stop.
    @DefaultValue("10m") Duration excessiveIdleThreshold,
    // Task 3.3's own silence window: the minimum time between two alerts of
    // the same (vehicle, type, context) while the condition stays
    // continuously active. Long enough that a several-minute sustained
    // episode (spec.md's own worked example, test 5.6) never produces a
    // second alert; short enough that a dispatcher is still re-notified
    // about a genuinely long-running condition instead of only ever hearing
    // about it once.
    @DefaultValue("15m") Duration silenceWindow
) {
}
