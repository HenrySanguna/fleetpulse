package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void distanceOneDegreeOfLatitudeApartMatchesTheKnownReferenceValue() {
        GeoPoint paris = new GeoPoint(48.8566, 2.3522, T0);
        GeoPoint oneDegreeSouth = new GeoPoint(47.8566, 2.3522, T0);

        double distance = Geo.distanceMeters(paris, oneDegreeSouth);

        assertThat(distance).isCloseTo(111_195.0, within(100.0));
    }

    @Test
    void distanceAcrossTheAntimeridianIsTheShortWayAround() {
        GeoPoint justWestOfAntimeridian = new GeoPoint(0.0, 179.9, T0);
        GeoPoint justEastOfAntimeridian = new GeoPoint(0.0, -179.9, T0);

        double distance = Geo.distanceMeters(justWestOfAntimeridian, justEastOfAntimeridian);

        assertThat(distance).isCloseTo(22_239.0, within(200.0));
    }

    @Test
    void distanceFromAPointToItselfIsZero() {
        GeoPoint point = new GeoPoint(40.4168, -3.7038, T0);

        double distance = Geo.distanceMeters(point, point);

        assertThat(distance).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void bearingDueEastAlongTheEquatorIsNinetyDegrees() {
        GeoPoint origin = new GeoPoint(0.0, 0.0, T0);
        GeoPoint east = new GeoPoint(0.0, 1.0, T0);

        assertThat(Geo.bearingDegrees(origin, east)).isCloseTo(90.0, within(0.1));
    }

    @Test
    void bearingDueNorthAlongAMeridianIsZeroDegrees() {
        GeoPoint origin = new GeoPoint(0.0, 0.0, T0);
        GeoPoint north = new GeoPoint(1.0, 0.0, T0);

        assertThat(Geo.bearingDegrees(origin, north)).isCloseTo(0.0, within(0.1));
    }

    @Test
    void bearingDueWestIsNormalizedIntoTheZeroToThreeSixtyRange() {
        GeoPoint origin = new GeoPoint(0.0, 1.0, T0);
        GeoPoint west = new GeoPoint(0.0, 0.0, T0);

        assertThat(Geo.bearingDegrees(origin, west)).isCloseTo(270.0, within(0.1));
    }

    @Test
    void speedIsEmptyWhenTheSecondInstantIsEqualToTheFirst() {
        GeoPoint a = new GeoPoint(0.0, 0.0, T0);
        GeoPoint b = new GeoPoint(0.0, 1.0, T0);

        OptionalDouble speed = Geo.speedKmh(a, b);

        assertThat(speed).isEmpty();
    }

    @Test
    void speedIsEmptyWhenTheSecondInstantIsBeforeTheFirstBecauseADeviceResentBufferedData() {
        GeoPoint a = new GeoPoint(0.0, 0.0, T0);
        GeoPoint b = new GeoPoint(0.0, 1.0, T0.minusSeconds(60));

        OptionalDouble speed = Geo.speedKmh(a, b);

        assertThat(speed).isEmpty();
    }

    @Test
    void speedIsComputedFromDistanceAndElapsedTimeWhenTimeAdvances() {
        GeoPoint a = new GeoPoint(48.8566, 2.3522, T0);
        GeoPoint b = new GeoPoint(47.8566, 2.3522, T0.plusSeconds(3600));

        OptionalDouble speed = Geo.speedKmh(a, b);

        assertThat(speed).isPresent();
        assertThat(speed.getAsDouble()).isCloseTo(111.195, within(0.5));
    }

    @Test
    void anImpossibleJumpOverAFewSecondsIsMarkedImplausible() {
        GeoPoint prev = new GeoPoint(40.4168, -3.7038, T0);
        GeoPoint next = new GeoPoint(41.3851, 2.1734, T0.plusSeconds(2));

        assertThat(Geo.isImplausible(prev, next, 180.0)).isTrue();
    }

    @Test
    void aFastButAchievableMoveIsNotMarkedImplausible() {
        GeoPoint prev = new GeoPoint(48.8566, 2.3522, T0);
        GeoPoint next = new GeoPoint(47.8566, 2.3522, T0.plusSeconds(3600));

        assertThat(Geo.isImplausible(prev, next, 180.0)).isFalse();
    }
}
