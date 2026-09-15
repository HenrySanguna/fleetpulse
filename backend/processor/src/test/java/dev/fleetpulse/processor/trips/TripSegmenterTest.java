package dev.fleetpulse.processor.trips;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tasks 1.2/1.4/1.5, pure and database-free: MotionState is supplied
// directly per position (not replayed through VehicleMotionStreakTracker
// here) so this proves TripSegmenter's own grouping/metrics logic in
// isolation from motion classification -- the same layering
// FenceMembershipDetectorTest (05-add-geofencing) already established for
// its own pure geo-core decision function.
class TripSegmenterTest {

    private static final UUID VEHICLE_ID = UUID.randomUUID();
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration STOP_THRESHOLD = Duration.ofMinutes(5);

    // Test 5.1: a continuous trace with two stops longer than the
    // threshold (320s and 360s, both >= the 300s threshold) is split into
    // exactly three trips. The trace ends right where the second stop
    // closes the third trip, so there is no trailing open segment left to
    // worry about for this scenario.
    @Test
    void twoLongStopsProduceExactlyThreeTrips() {
        Fixture fixture = new Fixture();
        fixture.moving(0, 40).moving(60, 45).moving(120, 42);
        fixture.stopped(180).stopped(200).stopped(500); // 320s run, closes trip A
        fixture.moving(560, 50).moving(620, 48).moving(680, 52);
        fixture.stopped(740).stopped(1100); // 360s run, closes trip B
        fixture.moving(1160, 30).moving(1220, 33).moving(1280, 31);
        fixture.stopped(1340).stopped(1700); // 360s run, closes trip C

        List<TripCandidate> trips = fixture.segment();

        assertThat(trips).hasSize(3);
        assertThat(trips.get(0).startedAt()).isEqualTo(BASE.plusSeconds(0));
        assertThat(trips.get(0).endedAt()).isEqualTo(BASE.plusSeconds(120));
        assertThat(trips.get(0).maxSpeedKmh()).isEqualTo(45.0);

        assertThat(trips.get(1).startedAt()).isEqualTo(BASE.plusSeconds(560));
        assertThat(trips.get(1).endedAt()).isEqualTo(BASE.plusSeconds(680));
        assertThat(trips.get(1).maxSpeedKmh()).isEqualTo(52.0);

        assertThat(trips.get(2).startedAt()).isEqualTo(BASE.plusSeconds(1160));
        assertThat(trips.get(2).endedAt()).isEqualTo(BASE.plusSeconds(1280));
        assertThat(trips.get(2).maxSpeedKmh()).isEqualTo(33.0);

        trips.forEach(trip -> {
            assertThat(trip.vehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(trip.organizationId()).isEqualTo(ORGANIZATION_ID);
            assertThat(trip.distanceKm()).isGreaterThan(0.0);
            assertThat(trip.avgSpeedKmh()).isGreaterThan(0.0);
            assertThat(trip.idleSecs()).isZero();
        });
    }

    // Test 5.2: a brief stop (20s, well under the 300s threshold) does not
    // split the trip -- its duration is instead folded into the resulting
    // single trip's own idle_secs. The trace is closed off by a final
    // qualifying stop so a single trip is cleanly emitted.
    @Test
    void aShortStopBelowThresholdDoesNotSplitTheTrip() {
        Fixture fixture = new Fixture();
        fixture.moving(0, 40).moving(60, 45).moving(120, 42);
        fixture.stopped(150).stopped(170); // only 20s -- a red light, not a "parada prolongada"
        fixture.moving(200, 38).moving(260, 41).moving(320, 39);
        fixture.stopped(380).stopped(700); // 320s, qualifies and closes the trip

        List<TripCandidate> trips = fixture.segment();

        assertThat(trips).hasSize(1);
        TripCandidate trip = trips.get(0);
        assertThat(trip.startedAt()).isEqualTo(BASE.plusSeconds(0));
        assertThat(trip.endedAt()).isEqualTo(BASE.plusSeconds(320));
        assertThat(trip.idleSecs()).isEqualTo(20L);
    }

    // A MotionState that VehicleMotionStreakTracker could not classify
    // (null -- no speedKmh reported for that position) must never fragment
    // an otherwise-continuous MOVING trip on its own.
    @Test
    void aPositionWithUnknownMotionStateDoesNotFragmentAMovingTrip() {
        Fixture fixture = new Fixture();
        fixture.moving(0, 40).unknown(60).moving(120, 42);
        fixture.stopped(180).stopped(500); // closes the trip

        List<TripCandidate> trips = fixture.segment();

        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).startedAt()).isEqualTo(BASE.plusSeconds(0));
        assertThat(trips.get(0).endedAt()).isEqualTo(BASE.plusSeconds(120));
    }

    // The trailing segment -- whichever kind it is -- is never emitted:
    // TripSegmentationTask relies on exactly this to keep reprocessing
    // idempotent (task 1.5), since the next run's watermark only ever
    // advances past a trip that was actually inserted.
    @Test
    void aTrailingSegmentStillMovingWithNoClosingStopIsNeverEmitted() {
        Fixture fixture = new Fixture();
        fixture.moving(0, 40).moving(60, 45).moving(120, 42);

        assertThat(fixture.segment()).isEmpty();
    }

    @Test
    void aTrailingStopThatHasNotYetReachedTheThresholdIsNeverEmitted() {
        Fixture fixture = new Fixture();
        fixture.moving(0, 40).moving(60, 45).moving(120, 42);
        fixture.stopped(150).stopped(170); // only 20s so far -- still ambiguous

        assertThat(fixture.segment()).isEmpty();
    }

    // A lone position surrounded by qualifying stops on both sides carries
    // no distance/duration worth recording, so it is dropped rather than
    // persisted as a zero-length trip.
    @Test
    void aSinglePositionSegmentBetweenTwoStopsIsDropped() {
        Fixture fixture = new Fixture();
        fixture.stopped(0).stopped(320); // closes nothing (tripStartIdx already null at start)
        fixture.moving(380, 40); // lone position
        fixture.stopped(410).stopped(730); // closes the lone-position "trip" -- dropped

        assertThat(fixture.segment()).isEmpty();
    }

    // A small test DSL: builds parallel PositionSample/VehicleMotionUpdate
    // lists second-by-second offset from BASE, then calls the segmenter
    // under test.
    private static final class Fixture {
        private final List<PositionSample> positions = new ArrayList<>();
        private final List<VehicleMotionUpdate> motionUpdates = new ArrayList<>();

        Fixture moving(long offsetSeconds, double speedKmh) {
            add(offsetSeconds, speedKmh, MotionState.MOVING);
            return this;
        }

        Fixture stopped(long offsetSeconds) {
            add(offsetSeconds, 0.0, MotionState.STOPPED);
            return this;
        }

        Fixture unknown(long offsetSeconds) {
            positions.add(new PositionSample(BASE.plusSeconds(offsetSeconds), 4.71, -74.07 + offsetSeconds * 0.0001, null, null));
            motionUpdates.add(new VehicleMotionUpdate(null, null, null));
            return this;
        }

        private void add(long offsetSeconds, double speedKmh, MotionState state) {
            positions.add(new PositionSample(BASE.plusSeconds(offsetSeconds), 4.71, -74.07 + offsetSeconds * 0.0001, speedKmh, true));
            motionUpdates.add(new VehicleMotionUpdate(state, null, null));
        }

        List<TripCandidate> segment() {
            return TripSegmenter.segment(VEHICLE_ID, ORGANIZATION_ID, positions, motionUpdates, STOP_THRESHOLD);
        }
    }
}
