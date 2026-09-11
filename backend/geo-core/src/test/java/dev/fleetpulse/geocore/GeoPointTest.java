package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GeoPointTest {

    @Test
    void exposesLatLonAndInstantAsGiven() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        GeoPoint point = new GeoPoint(40.4168, -3.7038, at);

        assertThat(point.lat()).isEqualTo(40.4168);
        assertThat(point.lon()).isEqualTo(-3.7038);
        assertThat(point.at()).isEqualTo(at);
    }
}
