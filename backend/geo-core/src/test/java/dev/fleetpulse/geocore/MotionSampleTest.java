package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MotionSampleTest {

    @Test
    void exposesSpeedEngineSignalAndStreaksAsGiven() {
        MotionSample sample = new MotionSample(2.5, true, Duration.ofSeconds(10), Duration.ZERO);

        assertThat(sample.speedKmh()).isEqualTo(2.5);
        assertThat(sample.engineOn()).isTrue();
        assertThat(sample.lowSpeedStreak()).isEqualTo(Duration.ofSeconds(10));
        assertThat(sample.highSpeedStreak()).isEqualTo(Duration.ZERO);
    }
}
