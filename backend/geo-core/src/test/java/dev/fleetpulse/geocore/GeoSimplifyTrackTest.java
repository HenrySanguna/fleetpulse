package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeoSimplifyTrackTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static GeoPoint pointAt(double lat, double lon) {
        return new GeoPoint(lat, lon, T0);
    }

    @Test
    void keepsAllPointsWhenTheDeviationExceedsTheTolerance() {
        GeoPoint start = pointAt(0.0, 0.0);
        GeoPoint deviated = pointAt(0.001, 0.01);
        GeoPoint end = pointAt(0.0, 0.02);

        List<GeoPoint> simplified = Geo.simplifyTrack(List.of(start, deviated, end), 50.0);

        assertThat(simplified).containsExactly(start, deviated, end);
    }

    @Test
    void collapsesToTheEndpointsWhenTheDeviationIsWithinTolerance() {
        GeoPoint start = pointAt(0.0, 0.0);
        GeoPoint deviated = pointAt(0.001, 0.01);
        GeoPoint end = pointAt(0.0, 0.02);

        List<GeoPoint> simplified = Geo.simplifyTrack(List.of(start, deviated, end), 200.0);

        assertThat(simplified).containsExactly(start, end);
    }

    @Test
    void returnsShortTracksUnchanged() {
        GeoPoint start = pointAt(0.0, 0.0);
        GeoPoint end = pointAt(0.0, 0.02);

        List<GeoPoint> simplified = Geo.simplifyTrack(List.of(start, end), 50.0);

        assertThat(simplified).containsExactly(start, end);
    }

    @Test
    void alwaysPreservesTheFirstAndLastPointOfALongerTrackWithMixedDeviations() {
        GeoPoint first = pointAt(0.0, 0.0);
        GeoPoint smallDeviation = pointAt(0.00005, 0.01);
        GeoPoint largeDeviation = pointAt(0.001, 0.02);
        GeoPoint onTheLine = pointAt(0.0, 0.03);
        GeoPoint last = pointAt(0.0, 0.04);

        List<GeoPoint> track = List.of(first, smallDeviation, largeDeviation, onTheLine, last);
        List<GeoPoint> simplified = Geo.simplifyTrack(track, 50.0);

        assertThat(simplified.get(0)).isEqualTo(first);
        assertThat(simplified.get(simplified.size() - 1)).isEqualTo(last);
        assertThat(simplified).contains(largeDeviation);
    }
}
