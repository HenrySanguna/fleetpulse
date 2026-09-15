package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FenceMembershipConfigTest {

    @Test
    void acceptsAtLeastOneConfirmationReadingWithANonNegativeDuration() {
        FenceMembershipConfig cfg = new FenceMembershipConfig(3, Duration.ofSeconds(60));

        assertThat(cfg.confirmationReadings()).isEqualTo(3);
        assertThat(cfg.confirmationDuration()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsFewerThanOneConfirmationReading() {
        assertThatThrownBy(() -> new FenceMembershipConfig(0, Duration.ofSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeConfirmationDuration() {
        assertThatThrownBy(() -> new FenceMembershipConfig(3, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
