package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Same fixtures VehicleMotionStreakTrackerTest originally used to prove
// this exact streak-tracking scaffolding, now exercised directly against
// the shared, pure MotionReplay.next() rather than through processor's
// persistence-oriented VehicleMotionUpdate/VehicleMotionSnapshot wrapper.
class MotionReplayTest {

    private static final MotionConfig CFG = new MotionConfig(5.0, 12.0, Duration.ofSeconds(30));

    @Test
    void aVehicleNeverSeenBeforeDefaultsToStoppedAndStartsALowSpeedStreak() {
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");

        MotionReplay.Transition transition = MotionReplay.next(null, null, null, 0.0, false, recordedAt, CFG);

        assertThat(transition.motionState()).isEqualTo(MotionState.STOPPED);
        assertThat(transition.lowSpeedStreakStartedAt()).isEqualTo(recordedAt);
        assertThat(transition.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aSustainedHighSpeedStreakAcrossMultipleReadingsEventuallyTransitionsToMoving() {
        Instant t0 = Instant.parse("2026-09-11T10:00:00Z");

        MotionReplay.Transition first = MotionReplay.next(null, null, null, 20.0, false, t0, CFG);
        assertThat(first.motionState()).isEqualTo(MotionState.STOPPED);
        assertThat(first.highSpeedStreakStartedAt()).isEqualTo(t0);

        MotionReplay.Transition second = MotionReplay.next(
            first.motionState(), first.lowSpeedStreakStartedAt(), first.highSpeedStreakStartedAt(), 20.0, false, t0.plusSeconds(15), CFG);
        assertThat(second.motionState()).isEqualTo(MotionState.STOPPED);

        MotionReplay.Transition third = MotionReplay.next(
            second.motionState(), second.lowSpeedStreakStartedAt(), second.highSpeedStreakStartedAt(), 20.0, false, t0.plusSeconds(31), CFG);
        assertThat(third.motionState()).isEqualTo(MotionState.MOVING);
        assertThat(third.highSpeedStreakStartedAt()).isEqualTo(t0);
    }

    @Test
    void speedInTheDeadZoneClearsBothStreaksWithoutChangingMotionState() {
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");

        MotionReplay.Transition transition = MotionReplay.next(
            MotionState.MOVING, null, recordedAt.minusSeconds(20), 8.0, false, recordedAt, CFG);

        assertThat(transition.motionState()).isEqualTo(MotionState.MOVING);
        assertThat(transition.lowSpeedStreakStartedAt()).isNull();
        assertThat(transition.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aReadingWithoutSpeedKmhCarriesTheKnownStateForwardUnchanged() {
        Instant recordedAt = Instant.parse("2026-09-11T10:00:00Z");

        MotionReplay.Transition transition = MotionReplay.next(
            MotionState.IDLING, recordedAt.minusSeconds(10), null, null, true, recordedAt, CFG);

        assertThat(transition.motionState()).isEqualTo(MotionState.IDLING);
        assertThat(transition.lowSpeedStreakStartedAt()).isEqualTo(recordedAt.minusSeconds(10));
        assertThat(transition.highSpeedStreakStartedAt()).isNull();
    }

    @Test
    void aFirstEverReadingWithoutSpeedKmhLeavesEverythingNull() {
        MotionReplay.Transition transition = MotionReplay.next(
            null, null, null, null, true, Instant.parse("2026-09-11T10:00:00Z"), CFG);

        assertThat(transition.motionState()).isNull();
        assertThat(transition.lowSpeedStreakStartedAt()).isNull();
        assertThat(transition.highSpeedStreakStartedAt()).isNull();
    }
}
