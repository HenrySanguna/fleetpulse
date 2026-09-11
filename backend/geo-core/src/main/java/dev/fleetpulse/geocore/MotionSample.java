package dev.fleetpulse.geocore;

import java.time.Duration;

public record MotionSample(double speedKmh, boolean engineOn, Duration lowSpeedStreak, Duration highSpeedStreak) {
}
