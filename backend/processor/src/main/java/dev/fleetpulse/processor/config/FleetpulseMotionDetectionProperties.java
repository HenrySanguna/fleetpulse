package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 4.2: thresholds and minimum stable duration fed into geo-core's
// MotionConfig for MotionDetector.next(). Neither design.md nor proposal.md
// states required numbers, same precedent as WU4's implausibility threshold
// and WU4's buffer size/interval: the defaults mirror MotionDetectorTest's
// own fixture (5 km/h stop, 12 km/h start, 30s minimum stable duration) --
// values documented in geo-core as realistic enough to ignore GPS jitter on
// a parked vehicle while still confirming genuine motion promptly.
// startThresholdKmh must stay strictly above stopThresholdKmh (MotionConfig's
// own compact constructor enforces this) to avoid oscillation between MOVING
// and STOPPED/IDLING on noisy samples near a single shared threshold.
@ConfigurationProperties(prefix = "fleetpulse.telemetry.motion")
public record FleetpulseMotionDetectionProperties(
    @DefaultValue("5") double stopThresholdKmh,
    @DefaultValue("12") double startThresholdKmh,
    @DefaultValue("30s") Duration minStableDuration
) {
}
