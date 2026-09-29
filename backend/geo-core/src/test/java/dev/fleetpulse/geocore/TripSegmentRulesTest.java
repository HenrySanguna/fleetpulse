package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Same layering FenceMembershipDetectorTest/MotionDetectorTest already use
// for their own pure geo-core decision functions -- exercises segment()'s
// stop/dwell state machine and metrics()'s per-span distance/idle/speed
// calculation directly, hand-supplying MotionState per position (not
// replayed through MotionReplay here) so the segmentation rule is proven in
// isolation, the same separation TripSegmenterTest originally established.
class TripSegmentRulesTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration STOP_THRESHOLD = Duration.ofMinutes(5);

    @Test
    void rejectsMismatchedMotionStatesAndRecordedAtLists() {
        assertThatThrownBy(() -> TripSegmentRules.segment(
            List.of(MotionState.MOVING), List.of(BASE, BASE.plusSeconds(1)), STOP_THRESHOLD))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoLongStopsProduceExactlyThreeClosedSegmentsAndNoOpenOne() {
        Fixture fixture = new Fixture();
        fixture.moving(0).moving(60).moving(120);
        fixture.stopped(180).stopped(200).stopped(500); // 320s run, closes segment A
        fixture.moving(560).moving(620).moving(680);
        fixture.stopped(740).stopped(1100); // 360s run, closes segment B
        fixture.moving(1160).moving(1220).moving(1280);
        fixture.stopped(1340).stopped(1700); // 360s run, closes segment C

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).hasSize(3);
        assertThat(segments).allMatch(s -> !s.isOpen());
        assertThat(segments.get(0)).isEqualTo(new TripSegmentRules.Segment(0, 2));
        assertThat(segments.get(1)).isEqualTo(new TripSegmentRules.Segment(6, 8));
        assertThat(segments.get(2)).isEqualTo(new TripSegmentRules.Segment(11, 13));
    }

    @Test
    void aShortStopBelowThresholdDoesNotSplitTheSegment() {
        Fixture fixture = new Fixture();
        fixture.moving(0).moving(60).moving(120);
        fixture.stopped(150).stopped(170); // only 20s -- under the threshold
        fixture.moving(200).moving(260).moving(320);
        fixture.stopped(380).stopped(700); // 320s, qualifies and closes

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).containsExactly(new TripSegmentRules.Segment(0, 7));
    }

    // A not-moving run that keeps going well past the moment it already
    // closed its trip must not attempt to close a second time -- exercises
    // the runAlreadyClosedTrip short-circuit on the positions after the
    // threshold was first reached.
    @Test
    void aNotMovingRunContinuingPastTheThresholdOnlyClosesOnce() {
        Fixture fixture = new Fixture();
        fixture.moving(0).moving(60);
        fixture.stopped(90).stopped(400).stopped(700).stopped(1000); // crosses 300s at index 2, then keeps going

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).containsExactly(new TripSegmentRules.Segment(0, 1));
    }

    // A null MotionState (a position MotionReplay could not classify) must
    // never fragment an otherwise-continuous MOVING segment on its own.
    @Test
    void aPositionWithUnknownMotionStateDoesNotFragmentAMovingSegment() {
        Fixture fixture = new Fixture();
        fixture.moving(0).unknown(60).moving(120);
        fixture.stopped(180).stopped(500); // closes the segment

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).containsExactly(new TripSegmentRules.Segment(0, 2));
    }

    @Test
    void aTrailingSegmentStillMovingWithNoClosingStopIsReportedAsOpen() {
        Fixture fixture = new Fixture();
        fixture.moving(0).moving(60).moving(120);

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).containsExactly(new TripSegmentRules.Segment(0, null));
        assertThat(segments.get(0).isOpen()).isTrue();
    }

    @Test
    void aTrailingStopThatHasNotYetReachedTheThresholdIsReportedAsOpenFromTheTripStart() {
        Fixture fixture = new Fixture();
        fixture.moving(0).moving(60).moving(120);
        fixture.stopped(150).stopped(170); // only 20s so far -- still ambiguous

        List<TripSegmentRules.Segment> segments = fixture.segment();

        assertThat(segments).containsExactly(new TripSegmentRules.Segment(0, null));
    }

    @Test
    void aSinglePositionSegmentBetweenTwoStopsIsDropped() {
        Fixture fixture = new Fixture();
        fixture.stopped(0).stopped(320); // closes nothing (tripStartIdx already at its own default)
        fixture.moving(380); // lone position
        fixture.stopped(410).stopped(730); // closes the lone-position span -- dropped

        assertThat(fixture.segment()).isEmpty();
    }

    @Test
    void aSingleTrailingPositionIsNotReportedAsOpen() {
        Fixture fixture = new Fixture();
        fixture.moving(0);

        assertThat(fixture.segment()).isEmpty();
    }

    @Test
    void metricsAccumulatesDistanceIdleAndMaxSpeedOverTheGivenRange() {
        List<PositionSample> positions = List.of(
            new PositionSample(BASE, 4.71, -74.070, 40.0, false),
            new PositionSample(BASE.plusSeconds(30), 4.71, -74.069, 45.0, false),
            new PositionSample(BASE.plusSeconds(60), 4.71, -74.068, null, false), // unclassifiable leg endpoint
            new PositionSample(BASE.plusSeconds(90), 4.71, -74.068, 0.0, false),
            new PositionSample(BASE.plusSeconds(150), 4.71, -74.068, 3.0, true),
            new PositionSample(BASE.plusSeconds(180), 4.71, -74.067, 20.0, false)
        );
        List<MotionState> states = Arrays.asList(
            MotionState.MOVING, MotionState.MOVING, null, MotionState.STOPPED, MotionState.IDLING, MotionState.MOVING
        );

        TripSegmentRules.Metrics metrics = TripSegmentRules.metrics(positions, states, 0, 5, positions.get(0).recordedAt());

        assertThat(metrics.distanceKm()).isGreaterThan(0.0);
        assertThat(metrics.maxSpeedKmh()).isEqualTo(45.0);
        // Only the leg between the STOPPED and IDLING endpoints (index 3 -> 4, 60s) counts as
        // idle: the null-state leg, the STOPPED-preceded-by-null leg, and the IDLING-to-MOVING
        // resuming leg (index 4 -> 5) are all transition legs.
        assertThat(metrics.idleSecs()).isEqualTo(60L);
        assertThat(metrics.durationSecs()).isEqualTo(180L);
        assertThat(metrics.avgSpeedKmh()).isGreaterThan(0.0);
    }

    // startedAt can be earlier than positions.get(startIdx).recordedAt() (a
    // clipped query window's own start) -- durationSecs/avgSpeedKmh are
    // measured from that caller-supplied instant, not the first position's.
    @Test
    void metricsMeasuresDurationFromTheCallerSuppliedStartedAtNotTheFirstPosition() {
        List<PositionSample> positions = List.of(
            new PositionSample(BASE, 4.71, -74.070, 40.0, false),
            new PositionSample(BASE.plusSeconds(60), 4.71, -74.069, 40.0, false)
        );
        List<MotionState> states = List.of(MotionState.MOVING, MotionState.MOVING);
        Instant clippedStart = BASE.minusSeconds(60);

        TripSegmentRules.Metrics metrics = TripSegmentRules.metrics(positions, states, 0, 1, clippedStart);

        assertThat(metrics.durationSecs()).isEqualTo(120L);
    }

    // durationSecs can be zero (or negative, for a malformed startedAt) --
    // avgSpeedKmh falls back to 0.0 rather than dividing by zero.
    @Test
    void metricsAvgSpeedFallsBackToZeroWhenDurationIsNotPositive() {
        List<PositionSample> positions = List.of(
            new PositionSample(BASE, 4.71, -74.070, 40.0, false),
            new PositionSample(BASE, 4.71, -74.069, 40.0, false)
        );
        List<MotionState> states = List.of(MotionState.MOVING, MotionState.MOVING);

        TripSegmentRules.Metrics metrics = TripSegmentRules.metrics(positions, states, 0, 1, BASE);

        assertThat(metrics.durationSecs()).isZero();
        assertThat(metrics.avgSpeedKmh()).isEqualTo(0.0);
        assertThat(metrics.distanceKm()).isGreaterThan(0.0);
    }

    // A small test DSL, same spirit as the original TripSegmenterTest/
    // InProgressTripCalculatorTest fixtures: builds parallel MotionState/
    // Instant lists second-by-second offset from BASE.
    private static final class Fixture {
        private final List<MotionState> states = new ArrayList<>();
        private final List<Instant> recordedAt = new ArrayList<>();

        Fixture moving(long offsetSeconds) {
            add(offsetSeconds, MotionState.MOVING);
            return this;
        }

        Fixture stopped(long offsetSeconds) {
            add(offsetSeconds, MotionState.STOPPED);
            return this;
        }

        Fixture unknown(long offsetSeconds) {
            states.add(null);
            recordedAt.add(BASE.plusSeconds(offsetSeconds));
            return this;
        }

        private void add(long offsetSeconds, MotionState state) {
            states.add(state);
            recordedAt.add(BASE.plusSeconds(offsetSeconds));
        }

        List<TripSegmentRules.Segment> segment() {
            return TripSegmentRules.segment(states, recordedAt, STOP_THRESHOLD);
        }
    }
}
