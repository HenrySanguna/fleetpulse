package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSampleTest {

    @Test
    void exposesRecordedAtLocationSpeedAndIgnitionAsGiven() {
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");

        PositionSample sample = new PositionSample(recordedAt, 4.71, -74.07, 40.0, true);

        assertThat(sample.recordedAt()).isEqualTo(recordedAt);
        assertThat(sample.lat()).isEqualTo(4.71);
        assertThat(sample.lon()).isEqualTo(-74.07);
        assertThat(sample.speedKmh()).isEqualTo(40.0);
        assertThat(sample.ignition()).isTrue();
    }
}
