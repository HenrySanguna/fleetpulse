package dev.fleetpulse.geocore;

import java.time.Duration;

public record FenceMembershipConfig(int confirmationReadings, Duration confirmationDuration) {

    public FenceMembershipConfig {
        if (confirmationReadings < 1) {
            throw new IllegalArgumentException(
                "confirmationReadings must be at least 1: " + confirmationReadings
            );
        }
        if (confirmationDuration.isNegative()) {
            throw new IllegalArgumentException(
                "confirmationDuration must not be negative: " + confirmationDuration
            );
        }
    }
}
