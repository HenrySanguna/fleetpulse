package dev.fleetpulse.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 10 (prod QA: activity report in-progress trip): mirrors processor's
// own FleetpulseMotionDetectionProperties (same prefix, same defaults) so
// InProgressTripCalculator classifies raw positions with the SAME
// MotionConfig thresholds TripSegmenter's own trips are built from -- api
// cannot depend on processor (module boundary rule, already documented in
// VehicleTrackService/VehicleStateJdbcReader), and neither module's
// application.yml ever overrides this prefix in this repo (grepped: no
// override anywhere), so duplicating the defaults here carries no real risk
// of drifting from what the processor actually runs with today. If an
// operator ever tunes fleetpulse.telemetry.motion.* for the processor
// deployment, this copy must be updated by hand to match.
@ConfigurationProperties(prefix = "fleetpulse.telemetry.motion")
public record FleetpulseMotionDetectionProperties(
    @DefaultValue("5") double stopThresholdKmh,
    @DefaultValue("12") double startThresholdKmh,
    @DefaultValue("30s") Duration minStableDuration
) {
}
