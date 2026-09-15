package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FenceMembershipSampleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void acceptsAPointInsideBothTheStrictAndTheBufferedBoundary() {
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0);

        assertThat(sample.insideStrictBoundary()).isTrue();
        assertThat(sample.insideBufferedBoundary()).isTrue();
    }

    @Test
    void acceptsAPointInsideTheBufferedBoundaryButOutsideTheStrictBoundary() {
        FenceMembershipSample sample = new FenceMembershipSample(false, true, T0);

        assertThat(sample.insideStrictBoundary()).isFalse();
        assertThat(sample.insideBufferedBoundary()).isTrue();
    }

    @Test
    void acceptsAPointOutsideBothBoundaries() {
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0);

        assertThat(sample.insideStrictBoundary()).isFalse();
        assertThat(sample.insideBufferedBoundary()).isFalse();
    }

    @Test
    void rejectsAPointInsideTheStrictBoundaryButOutsideTheBufferedBoundary() {
        assertThatThrownBy(() -> new FenceMembershipSample(true, false, T0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
