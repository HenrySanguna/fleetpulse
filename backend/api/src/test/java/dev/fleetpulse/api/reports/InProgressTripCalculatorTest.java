package dev.fleetpulse.api.reports;

import dev.fleetpulse.api.reports.InProgressTripJdbcReader.MotionPositionSample;
import dev.fleetpulse.geocore.MotionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// Task 10, pure and database-free: same layering TripSegmenterTest already
// established for TripSegmenter itself -- exercises real speed traces (not
// hand-supplied MotionState) so the debounce/replay half
// (replayMotionStates(), mirroring VehicleMotionStreakTracker.nextFor()) is
// covered too, not just the grouping loop. Thresholds match
// TripSegmentationEndToEndTest's own fixture: 5 km/h stop / 12 km/h start /
// 30s minimum stable duration for MotionConfig, 300s (5 min) for
// stopThreshold.
class InProgressTripCalculatorTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration STOP_THRESHOLD = Duration.ofSeconds(300);
    private static final MotionConfig MOTION_CONFIG = new MotionConfig(5.0, 12.0, Duration.ofSeconds(30));

    // pos1(t=0,40kmh) pos2(t=30,40kmh) -> MOVING confirmed at pos2, but
    // tripStartIdx still lands on pos1 (index 0): nothing before it ever
    // closed a trip, so TripSegmenter's own state machine (mirrored in
    // InProgressTripCalculator) never advances tripStartIdx off its initial
    // value of 0 -- same worked example TripSegmentationEndToEndTest's own
    // insertMovingThenStoppedTrace() documents.
    @Test
    void aVehicleStillMovingAfterTheLastClosedTripIsInProgress() {
        Fixture fixture = new Fixture();
        fixture.sample(0, 40.0).sample(30, 40.0).sample(60, 40.0).sample(90, 40.0);

        Optional<ActivityInProgressTripResponse> result = fixture.calculate();

        assertThat(result).isPresent();
        ActivityInProgressTripResponse trip = result.get();
        assertThat(trip.startedAt()).isEqualTo(BASE);
        assertThat(trip.durationMinutes()).isEqualTo(1); // 90s / 60, integer division
        assertThat(trip.idleMinutes()).isZero();
        assertThat(trip.maxSpeedKmh()).isEqualTo(40.0);
        assertThat(trip.distanceKm()).isGreaterThan(0.0);
    }

    // Mirrors TripSegmentationEndToEndTest's own confirmation trace: two
    // confirmations (MOVING at pos2, STOPPED once the low-speed streak
    // reaches 30s) then the not-moving run keeps extending past the 300s
    // stopThreshold -- the trip this window would otherwise show is already
    // effectively closed, so no in-progress trip is returned.
    @Test
    void aVehicleStoppedLongEnoughIsNotInProgress() {
        Fixture fixture = new Fixture();
        fixture.sample(0, 40.0).sample(30, 40.0).sample(60, 40.0);
        fixture.sample(90, 0.0).sample(120, 0.0).sample(420, 0.0); // 300s not-moving run from t=120

        assertThat(fixture.calculate()).isEmpty();
    }

    // A brief stop under the threshold (20s) never closes anything -- the
    // whole span stays one open trip, and the stop's own duration folds
    // into idleMinutes, same as TripSegmenter.addTrip()'s idle_secs.
    @Test
    void aShortStopBelowThresholdStaysPartOfTheOpenTrip() {
        Fixture fixture = new Fixture();
        fixture.sample(0, 40.0).sample(30, 40.0).sample(60, 40.0);
        fixture.sample(90, 0.0).sample(120, 0.0); // 30s not-moving, well under 300s
        fixture.sample(150, 40.0).sample(180, 40.0);

        Optional<ActivityInProgressTripResponse> result = fixture.calculate();

        assertThat(result).isPresent();
        ActivityInProgressTripResponse trip = result.get();
        assertThat(trip.startedAt()).isEqualTo(BASE);
        assertThat(trip.idleMinutes()).isZero(); // 30s of idle rounds down to 0 whole minutes
    }

    // clippedToWindowStart=true means the queried window's lower bound is
    // the report's own `from`, not the vehicle's real last-closed-trip
    // boundary -- if the very first sample is already open (tripStartIdx
    // == 0), the true trip start might predate what this window can see,
    // so startedAt is reported as windowStart itself ("or the window
    // start", task 10's own wording) rather than the first sample's
    // timestamp.
    @Test
    void aTripAlreadyOpenAtTheClippedWindowStartReportsTheWindowStartAsStartedAt() {
        Fixture fixture = new Fixture();
        fixture.sample(0, 40.0).sample(30, 40.0).sample(60, 40.0);

        Optional<ActivityInProgressTripResponse> result = fixture.calculate(true, BASE.minusSeconds(120));

        assertThat(result).isPresent();
        assertThat(result.get().startedAt()).isEqualTo(BASE.minusSeconds(120));
    }

    // A single trailing sample after tripStartIdx carries no distance/
    // duration worth showing yet, same guard as TripSegmenter.addTrip().
    @Test
    void aSingleTrailingSampleIsNotYetInProgress() {
        Fixture fixture = new Fixture();
        fixture.sample(0, 40.0);

        assertThat(fixture.calculate()).isEmpty();
    }

    @Test
    void noPositionsAtAllMeansNoInProgressTrip() {
        assertThat(InProgressTripCalculator.calculate(List.of(), BASE, false, STOP_THRESHOLD, MOTION_CONFIG)).isEmpty();
    }

    // A small test DSL, same spirit as TripSegmenterTest's own Fixture:
    // builds a MotionPositionSample list second-by-second offset from BASE
    // (raw speed samples, not pre-classified MotionState -- calculate()
    // does its own replay), then calls the calculator under test.
    private static final class Fixture {
        private final List<MotionPositionSample> positions = new ArrayList<>();

        Fixture sample(long offsetSeconds, double speedKmh) {
            positions.add(new MotionPositionSample(BASE.plusSeconds(offsetSeconds), 4.71, -74.07 + offsetSeconds * 0.0001, speedKmh, false));
            return this;
        }

        Optional<ActivityInProgressTripResponse> calculate() {
            return calculate(false, BASE);
        }

        Optional<ActivityInProgressTripResponse> calculate(boolean clippedToWindowStart, Instant windowStart) {
            return InProgressTripCalculator.calculate(positions, windowStart, clippedToWindowStart, STOP_THRESHOLD, MOTION_CONFIG);
        }
    }
}
