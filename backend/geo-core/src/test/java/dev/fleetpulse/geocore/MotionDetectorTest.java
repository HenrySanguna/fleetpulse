package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MotionDetectorTest {

    private static final MotionConfig CFG = new MotionConfig(5.0, 12.0, Duration.ofSeconds(30));

    @Test
    void aParkedVehicleWithRealisticGpsDriftNeverTransitionsAwayFromStopped() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<GeoPoint> jitterTrack = List.of(
            new GeoPoint(40.41680, -3.70380, t0),
            new GeoPoint(40.41682, -3.70378, t0.plusSeconds(10)),
            new GeoPoint(40.41679, -3.70381, t0.plusSeconds(20)),
            new GeoPoint(40.41681, -3.70379, t0.plusSeconds(30)),
            new GeoPoint(40.41680, -3.70382, t0.plusSeconds(40)),
            new GeoPoint(40.41683, -3.70380, t0.plusSeconds(50))
        );

        MotionState state = MotionState.STOPPED;
        Duration lowSpeedStreak = Duration.ZERO;

        for (int i = 1; i < jitterTrack.size(); i++) {
            double speed = Geo.speedKmh(jitterTrack.get(i - 1), jitterTrack.get(i)).orElseThrow();
            assertThat(speed).isLessThan(CFG.stopThresholdKmh());

            lowSpeedStreak = lowSpeedStreak.plusSeconds(10);
            MotionSample sample = new MotionSample(speed, false, lowSpeedStreak, Duration.ZERO);

            state = MotionDetector.next(state, sample, CFG);

            assertThat(state).isEqualTo(MotionState.STOPPED);
        }
    }

    @Test
    void aRealStartupThatStaysAboveTheStartThresholdLongEnoughTransitionsToMoving() {
        MotionState state = MotionState.STOPPED;
        Duration highSpeedStreak = Duration.ZERO;

        for (int i = 0; i < 2; i++) {
            highSpeedStreak = highSpeedStreak.plusSeconds(10);
            MotionSample sample = new MotionSample(20.0, false, Duration.ZERO, highSpeedStreak);
            state = MotionDetector.next(state, sample, CFG);
            assertThat(state).isEqualTo(MotionState.STOPPED);
        }

        highSpeedStreak = highSpeedStreak.plusSeconds(10);
        MotionSample confirming = new MotionSample(20.0, false, Duration.ZERO, highSpeedStreak);

        state = MotionDetector.next(state, confirming, CFG);

        assertThat(state).isEqualTo(MotionState.MOVING);
    }

    @Test
    void staysMovingWhileSpeedIsStillAboveTheStopThreshold() {
        MotionSample sample = new MotionSample(20.0, false, Duration.ZERO, Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.MOVING, sample, CFG)).isEqualTo(MotionState.MOVING);
    }

    @Test
    void staysMovingWhileBelowStopThresholdButNotYetStableLongEnough() {
        MotionSample sample = new MotionSample(2.0, false, Duration.ofSeconds(10), Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.MOVING, sample, CFG)).isEqualTo(MotionState.MOVING);
    }

    @Test
    void confirmsStoppedWhenBelowStopThresholdLongEnoughAndEngineIsOff() {
        MotionSample sample = new MotionSample(2.0, false, Duration.ofSeconds(30), Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.MOVING, sample, CFG)).isEqualTo(MotionState.STOPPED);
    }

    @Test
    void confirmsIdlingWhenBelowStopThresholdLongEnoughAndEngineIsOn() {
        MotionSample sample = new MotionSample(2.0, true, Duration.ofSeconds(30), Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.MOVING, sample, CFG)).isEqualTo(MotionState.IDLING);
    }

    @Test
    void movesFromStoppedToIdlingWhenTheEngineTurnsOnWithoutMovement() {
        MotionSample sample = new MotionSample(2.0, true, Duration.ZERO, Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.STOPPED, sample, CFG)).isEqualTo(MotionState.IDLING);
    }

    @Test
    void staysStoppedWhenTheEngineStaysOffAndThereIsNoMovement() {
        MotionSample sample = new MotionSample(2.0, false, Duration.ZERO, Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.STOPPED, sample, CFG)).isEqualTo(MotionState.STOPPED);
    }

    @Test
    void movesFromIdlingToStoppedWhenTheEngineTurnsOff() {
        MotionSample sample = new MotionSample(2.0, false, Duration.ZERO, Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.IDLING, sample, CFG)).isEqualTo(MotionState.STOPPED);
    }

    @Test
    void staysIdlingWhenTheEngineStaysOn() {
        MotionSample sample = new MotionSample(2.0, true, Duration.ZERO, Duration.ZERO);

        assertThat(MotionDetector.next(MotionState.IDLING, sample, CFG)).isEqualTo(MotionState.IDLING);
    }
}
