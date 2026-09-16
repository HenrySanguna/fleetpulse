package dev.fleetpulse.processor.eta;

import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Task 2.2/2.3: pure, database-free proof of the calculation itself --
// mirrors TripSegmenterTest's own layering (WU1): the pure algorithm is unit
// tested directly, real-vehicle wiring is EtaEndToEndTest's job. No geo-core
// change is exercised here beyond Geo.distanceMeters, already covered by
// geo-core's own test suite.
class SinuosityEtaCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    // Same properties fixture shape newMotionStreakTracker() etc. use
    // elsewhere in this module: explicit values, not the @DefaultValue
    // annotations (this is a pure unit test, no Spring context).
    private static final FleetpulseEtaProperties PROPERTIES =
        new FleetpulseEtaProperties(1.3, 0.3, 5.0, 30.0, Duration.ofMinutes(15));

    private final SinuosityEtaCalculator calculator = new SinuosityEtaCalculator(PROPERTIES);

    // Roughly 111.19 km per degree of latitude at the equator: 1 degree
    // north is a clean, easily-hand-checked straight-line distance.
    @Test
    void appliesTheSinuosityFactorAndDividesByTheRecentAverageSpeed() {
        GeoPoint current = new GeoPoint(0.0, 0.0, NOW);
        GeoPoint destination = new GeoPoint(1.0, 0.0, NOW);

        EtaEstimate estimate = calculator.calculate(current, destination, 60.0);

        // straightLineKm ~= 111.19; adjusted = 111.19 * 1.3 ~= 144.55;
        // etaHours = 144.55 / 60 ~= 2.409h ~= 8674s.
        assertThat(estimate.etaSeconds()).isBetween(8600L, 8750L);
        assertThat(estimate.marginSeconds()).isEqualTo(Math.round(estimate.etaSeconds() * 0.3));
    }

    @Test
    void everyEstimateCarriesAPositiveMarginNeverABareDuration() {
        GeoPoint current = new GeoPoint(4.70, -74.05, NOW);
        GeoPoint destination = new GeoPoint(4.75, -74.00, NOW);

        EtaEstimate estimate = calculator.calculate(current, destination, 40.0);

        assertThat(estimate.marginSeconds()).isGreaterThan(0);
    }

    // FleetpulseEtaProperties.minEffectiveSpeedKmh's own contract: a
    // near-stopped vehicle (real recent average speed close to zero) must
    // still produce a finite, clearly-margined estimate, never an infinite
    // or NaN one.
    @Test
    void clampsANearZeroRecentAverageSpeedToTheConfiguredFloorInsteadOfDividingByZero() {
        GeoPoint current = new GeoPoint(4.70, -74.05, NOW);
        GeoPoint destination = new GeoPoint(4.71, -74.05, NOW);

        EtaEstimate estimate = calculator.calculate(current, destination, 0.0);

        // Distance is ~1.1 km; at the 5.0 km/h floor that is well under an
        // hour -- a small, finite, sane bound proving the floor was applied
        // rather than a division by (near) zero blowing up the result.
        assertThat(estimate.etaSeconds()).isBetween(0L, 3600L);
    }

    @Test
    void aCloserDestinationProducesAShorterEtaThanAFartherOneAtTheSameSpeed() {
        GeoPoint current = new GeoPoint(4.70, -74.05, NOW);
        GeoPoint near = new GeoPoint(4.705, -74.05, NOW);
        GeoPoint far = new GeoPoint(4.90, -74.05, NOW);

        EtaEstimate nearEstimate = calculator.calculate(current, near, 40.0);
        EtaEstimate farEstimate = calculator.calculate(current, far, 40.0);

        assertThat(nearEstimate.etaSeconds()).isLessThan(farEstimate.etaSeconds());
    }
}
