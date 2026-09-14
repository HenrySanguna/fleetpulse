package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 4.2: plain unit tests, no Testcontainers -- the streak-state
// computation is a pure function of (known vehicle_state snapshots, ordered
// messages), cleanly separable from JdbcTelemetryPositionWriter's JDBC
// upsert (task 4.1), the same way TelemetryPositionBufferTest already
// unit-tests flush-trigger timing against a fake writer instead of a real
// database.
class VehicleMotionStreakTrackerTest {

    private static final FleetpulseMotionDetectionProperties PROPERTIES =
        new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30));

    private final VehicleMotionStreakTracker tracker = new VehicleMotionStreakTracker(PROPERTIES);

    @Test
    void aVehicleNeverSeenBeforeDefaultsToStoppedAndStartsALowSpeedStreak() {
        UUID vehicleId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");
        TelemetryMessage message = telemetry(vehicleId, recordedAt, 0.0, false);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(Map.of(), List.of(message));

        assertThat(updates).hasSize(1);
        VehicleMotionUpdate update = updates.get(0);
        assertThat(update.motionState()).isEqualTo(MotionState.STOPPED);
        assertThat(update.lowSpeedStreakStartedAt()).isEqualTo(recordedAt);
        assertThat(update.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aSustainedHighSpeedStreakAcrossMultipleMessagesEventuallyTransitionsToMoving() {
        UUID vehicleId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-11T10:00:00Z");
        TelemetryMessage first = telemetry(vehicleId, t0, 20.0, false);
        TelemetryMessage second = telemetry(vehicleId, t0.plusSeconds(15), 20.0, false);
        TelemetryMessage third = telemetry(vehicleId, t0.plusSeconds(31), 20.0, false);

        List<VehicleMotionUpdate> firstPass = tracker.computeUpdates(Map.of(), List.of(first));
        Map<UUID, VehicleMotionSnapshot> afterFirst = snapshotAfter(vehicleId, first, firstPass.get(0));

        List<VehicleMotionUpdate> secondPass = tracker.computeUpdates(afterFirst, List.of(second));
        assertThat(secondPass.get(0).motionState()).isEqualTo(MotionState.STOPPED);
        Map<UUID, VehicleMotionSnapshot> afterSecond = snapshotAfter(vehicleId, second, secondPass.get(0));

        List<VehicleMotionUpdate> thirdPass = tracker.computeUpdates(afterSecond, List.of(third));

        assertThat(thirdPass.get(0).motionState()).isEqualTo(MotionState.MOVING);
        assertThat(thirdPass.get(0).highSpeedStreakStartedAt()).isEqualTo(t0);
    }

    @Test
    void speedInTheDeadZoneResetsBothStreaksWithoutChangingMotionState() {
        UUID vehicleId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");
        VehicleMotionSnapshot previous = new VehicleMotionSnapshot(
            recordedAt.minusSeconds(10), MotionState.MOVING, null, recordedAt.minusSeconds(20)
        );
        TelemetryMessage message = telemetry(vehicleId, recordedAt, 8.0, false);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(Map.of(vehicleId, previous), List.of(message));

        VehicleMotionUpdate update = updates.get(0);
        assertThat(update.motionState()).isEqualTo(MotionState.MOVING);
        assertThat(update.lowSpeedStreakStartedAt()).isNull();
        assertThat(update.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aMessageWithoutSpeedKmhCarriesTheKnownStateForwardUnchanged() {
        UUID vehicleId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");
        VehicleMotionSnapshot previous = new VehicleMotionSnapshot(
            recordedAt.minusSeconds(10), MotionState.IDLING, recordedAt.minusSeconds(10), null
        );
        TelemetryMessage message = new TelemetryMessage(vehicleId, recordedAt, 40.4, -3.7, null, null, true);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(Map.of(vehicleId, previous), List.of(message));

        VehicleMotionUpdate update = updates.get(0);
        assertThat(update.motionState()).isEqualTo(MotionState.IDLING);
        assertThat(update.lowSpeedStreakStartedAt()).isEqualTo(recordedAt.minusSeconds(10));
        assertThat(update.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aFirstEverMessageWithoutSpeedKmhLeavesEverythingNull() {
        UUID vehicleId = UUID.randomUUID();
        TelemetryMessage message = new TelemetryMessage(vehicleId, Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7, null, null, null);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(Map.of(), List.of(message));

        VehicleMotionUpdate update = updates.get(0);
        assertThat(update.motionState()).isNull();
        assertThat(update.lowSpeedStreakStartedAt()).isNull();
        assertThat(update.highSpeedStreakStartedAt()).isNull();
    }

    // The guard (task 4.1) is per-message SQL; MotionDetector.next() is a
    // Java pure function. When one flush batch (task 3.1) contains two
    // messages for the same vehicle, the second must chain from the first's
    // computed result within this single call to computeUpdates(), not from
    // the pre-batch known state -- mirroring how the SQL WHERE clause
    // already makes location/recorded_at chain correctly across statements
    // in the same batch (WU6).
    @Test
    void twoMessagesForTheSameVehicleInOneBatchChainInOrder() {
        UUID vehicleId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-11T10:00:00Z");
        TelemetryMessage first = telemetry(vehicleId, t0, 20.0, false);
        TelemetryMessage second = telemetry(vehicleId, t0.plusSeconds(31), 20.0, false);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(Map.of(), List.of(first, second));

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).motionState()).isEqualTo(MotionState.STOPPED);
        assertThat(updates.get(1).motionState()).isEqualTo(MotionState.MOVING);
        assertThat(updates.get(1).highSpeedStreakStartedAt()).isEqualTo(t0);
    }

    // A stale message (older than the running state within the same batch)
    // must not become the new running "previous" for a later message --
    // exactly what the SQL guard's WHERE clause independently enforces for
    // location/recorded_at. If it wrongly did, the later message would
    // inherit the stale message's freshly-started high-speed streak
    // timestamp instead of the real previous state's, understating how
    // "used up" that streak already was and flipping the outcome to MOVING
    // instead of the correct STOPPED.
    @Test
    void aStaleMessageWithinABatchDoesNotBecomeTheChainedPreviousForALaterMessage() {
        UUID vehicleId = UUID.randomUUID();
        Instant tBefore = Instant.parse("2026-09-11T10:00:00Z");
        Instant stale = tBefore.minusSeconds(3600);
        Instant later = tBefore.plusSeconds(31);
        VehicleMotionSnapshot previous = new VehicleMotionSnapshot(tBefore, MotionState.IDLING, null, null);

        List<VehicleMotionUpdate> updates = tracker.computeUpdates(
            Map.of(vehicleId, previous),
            List.of(telemetry(vehicleId, stale, 20.0, false), telemetry(vehicleId, later, 20.0, false))
        );

        assertThat(updates.get(1).motionState()).isEqualTo(MotionState.STOPPED);
    }

    private static Map<UUID, VehicleMotionSnapshot> snapshotAfter(UUID vehicleId, TelemetryMessage message, VehicleMotionUpdate update) {
        return Map.of(
            vehicleId,
            new VehicleMotionSnapshot(message.recordedAt(), update.motionState(), update.lowSpeedStreakStartedAt(), update.highSpeedStreakStartedAt())
        );
    }

    private static TelemetryMessage telemetry(UUID vehicleId, Instant recordedAt, double speedKmh, boolean ignition) {
        return new TelemetryMessage(vehicleId, recordedAt, 40.4, -3.7, speedKmh, null, ignition);
    }
}
