package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CircleGeofenceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void containsAPointWellInsideTheRadius() {
        GeoPoint center = new GeoPoint(40.4168, -3.7038, T0);
        CircleGeofence fence = new CircleGeofence(center, 500.0);
        GeoPoint nearby = new GeoPoint(40.41700, -3.70380, T0);

        assertThat(fence.contains(nearby)).isTrue();
    }

    @Test
    void doesNotContainAPointWellOutsideTheRadius() {
        GeoPoint center = new GeoPoint(40.4168, -3.7038, T0);
        CircleGeofence fence = new CircleGeofence(center, 500.0);
        GeoPoint farAway = new GeoPoint(41.3851, 2.1734, T0);

        assertThat(fence.contains(farAway)).isFalse();
    }

    @Test
    void treatsAPointExactlyOnTheBoundaryAsInside() {
        GeoPoint center = new GeoPoint(0.0, 0.0, T0);
        double radiusMeters = Geo.distanceMeters(center, new GeoPoint(0.01, 0.0, T0));
        CircleGeofence fence = new CircleGeofence(center, radiusMeters);
        GeoPoint boundary = new GeoPoint(0.01, 0.0, T0);

        assertThat(fence.contains(boundary)).isTrue();
    }
}
