package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import dev.fleetpulse.processor.trips.PositionSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 4.1's aggregation core, pure and database-free: MotionState is
// supplied directly per position (not replayed through
// VehicleMotionStreakTracker here), the same layering
// TripSegmenterTest/FenceMembershipDetectorTest already established for
// their own pure geo-core decision functions.
class HourlyRollupAggregatorTest {

    private static final UUID VEHICLE_ID = UUID.randomUUID();
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();

    // Exact hour boundary, so bucketing/windowStart filtering is
    // unambiguous in every scenario below.
    private static final Instant HOUR_START = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void positionsWithinOneHourAggregateIntoASingleBucket() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(0, 40).moving(60, 45).moving(120, 42);

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        HourlyRollupCandidate row = rows.get(0);
        assertThat(row.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(row.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(row.hour()).isEqualTo(HOUR_START);
        assertThat(row.distanceKm()).isGreaterThan(0.0);
        assertThat(row.movingSecs()).isEqualTo(120L);
        assertThat(row.idleSecs()).isZero();
        assertThat(row.maxSpeedKmh()).isEqualTo(45.0);
    }

    @Test
    void idleLegsAccumulateIntoIdleSecsNotMovingSecs() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.stopped(0).stopped(60).stopped(150);

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        HourlyRollupCandidate row = rows.get(0);
        assertThat(row.idleSecs()).isEqualTo(150L);
        assertThat(row.movingSecs()).isZero();
    }

    @Test
    void aTransitionLegCountsTowardNeitherMovingNorIdleSecsButStillCountsDistance() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(0, 40).stopped(60);

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        HourlyRollupCandidate row = rows.get(0);
        assertThat(row.movingSecs()).isZero();
        assertThat(row.idleSecs()).isZero();
        assertThat(row.distanceKm()).isGreaterThan(0.0);
    }

    @Test
    void aLegCrossingAnHourBoundaryIsAttributedToTheEndPositionsHour() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(-60, 40); // 09:59:00 -- previous hour
        fixture.moving(120, 44); // 10:02:00 -- crosses into the next hour

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        HourlyRollupCandidate row = rows.get(0);
        assertThat(row.hour()).isEqualTo(HOUR_START);
        assertThat(row.movingSecs()).isEqualTo(180L);
        assertThat(row.distanceKm()).isGreaterThan(0.0);
    }

    @Test
    void anAnchorPositionBeforeWindowStartSuppliesLegContextButProducesNoBucketOfItsOwn() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(-30, 40); // 09:59:30 -- the anchor, strictly before windowStart
        fixture.moving(30, 44); // 10:00:30 -- first in-window position

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        HourlyRollupCandidate row = rows.get(0);
        assertThat(row.hour()).isEqualTo(HOUR_START);
        // The anchor -> first-in-window leg (60s, 09:59:30 to 10:00:30) is
        // still counted into the surviving bucket, proving the anchor gives
        // real leg context rather than simply being dropped outright.
        assertThat(row.movingSecs()).isEqualTo(60L);
        assertThat(row.distanceKm()).isGreaterThan(0.0);
    }

    @Test
    void aLoneAnchorWithNoFollowingInWindowPositionProducesNoRows() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(-30, 40); // only the anchor, nothing in-window follows it

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).isEmpty();
    }

    @Test
    void aPositionWithNoSpeedDoesNotAffectMaxSpeedKmhButStillFormsABucket() {
        Fixture fixture = new Fixture(HOUR_START);
        fixture.moving(0, 40).unknownSpeed(60).moving(120, 42);

        List<HourlyRollupCandidate> rows = fixture.aggregate(HOUR_START);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).maxSpeedKmh()).isEqualTo(42.0);
    }

    @Test
    void noPositionsProduceNoRows() {
        Fixture fixture = new Fixture(HOUR_START);

        assertThat(fixture.aggregate(HOUR_START)).isEmpty();
    }

    private static final class Fixture {
        private final Instant base;
        private final List<PositionSample> positions = new ArrayList<>();
        private final List<VehicleMotionUpdate> motionUpdates = new ArrayList<>();

        Fixture(Instant base) {
            this.base = base;
        }

        Fixture moving(long offsetSeconds, double speedKmh) {
            add(offsetSeconds, speedKmh, MotionState.MOVING);
            return this;
        }

        Fixture stopped(long offsetSeconds) {
            add(offsetSeconds, 0.0, MotionState.STOPPED);
            return this;
        }

        Fixture unknownSpeed(long offsetSeconds) {
            positions.add(new PositionSample(base.plusSeconds(offsetSeconds), 4.71, -74.07 + offsetSeconds * 0.0001, null, null));
            motionUpdates.add(new VehicleMotionUpdate(null, null, null));
            return this;
        }

        private void add(long offsetSeconds, double speedKmh, MotionState state) {
            positions.add(new PositionSample(base.plusSeconds(offsetSeconds), 4.71, -74.07 + offsetSeconds * 0.0001, speedKmh, true));
            motionUpdates.add(new VehicleMotionUpdate(state, null, null));
        }

        List<HourlyRollupCandidate> aggregate(Instant windowStart) {
            return HourlyRollupAggregator.aggregate(VEHICLE_ID, ORGANIZATION_ID, positions, motionUpdates, windowStart);
        }
    }
}
