package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MotionConfigTest {

    @Test
    void acceptsAStartThresholdGreaterThanTheStopThreshold() {
        MotionConfig cfg = new MotionConfig(3.0, 8.0, Duration.ofSeconds(30));

        assertThat(cfg.stopThresholdKmh()).isEqualTo(3.0);
        assertThat(cfg.startThresholdKmh()).isEqualTo(8.0);
        assertThat(cfg.minStableDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsAStartThresholdThatIsNotGreaterThanTheStopThreshold() {
        assertThatThrownBy(() -> new MotionConfig(8.0, 8.0, Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
