package dev.fleetpulse.geocore;

import java.time.Duration;

public record MotionConfig(double stopThresholdKmh, double startThresholdKmh, Duration minStableDuration) {

    public MotionConfig {
        if (startThresholdKmh <= stopThresholdKmh) {
            throw new IllegalArgumentException(
                "startThresholdKmh must be greater than stopThresholdKmh to avoid oscillation: "
                    + startThresholdKmh + " <= " + stopThresholdKmh
            );
        }
    }
}
