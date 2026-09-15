package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FenceMembershipDetectorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final FenceMembershipConfig CFG = new FenceMembershipConfig(3, Duration.ofSeconds(60));

    @Test
    void staysOutsideUnchangedWhenTheReadingStaysOutsideTheStrictBoundary() {
        FenceMembershipState prev = FenceMembershipState.confirmed(false, T0);
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0.plusSeconds(10));

        assertThat(FenceMembershipDetector.next(prev, sample, CFG)).isEqualTo(prev);
    }

    @Test
    void staysInsideUnchangedWhenTheReadingStaysInsideTheBufferedBoundary() {
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(10));

        assertThat(FenceMembershipDetector.next(prev, sample, CFG)).isEqualTo(prev);
    }

    @Test
    void aPointWithinTheBufferButOutsideTheStrictBoundaryNeverStartsAPendingExit() {
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(false, true, T0.plusSeconds(10));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next).isEqualTo(prev);
        assertThat(next.pendingSince()).isNull();
    }

    @Test
    void startsAPendingEntryWithoutConfirmingOnTheFirstDifferingReading() {
        FenceMembershipState prev = FenceMembershipState.confirmed(false, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(10));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isFalse();
        assertThat(next.since()).isEqualTo(T0);
        assertThat(next.pendingSince()).isEqualTo(T0.plusSeconds(10));
        assertThat(next.pendingReadingCount()).isEqualTo(1);
    }

    @Test
    void accumulatesTheStreakOnEachConsecutiveMatchingReadingWithoutConfirmingYet() {
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(10), 1);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(20));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isFalse();
        assertThat(next.pendingSince()).isEqualTo(T0.plusSeconds(10));
        assertThat(next.pendingReadingCount()).isEqualTo(2);
    }

    @Test
    void confirmsTheEntryOnceTheReadingStreakReachesTheConfirmationThreshold() {
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(10), 2);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(20));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isTrue();
        assertThat(next.since()).isEqualTo(T0.plusSeconds(20));
        assertThat(next.pendingSince()).isNull();
        assertThat(next.pendingReadingCount()).isZero();
    }

    @Test
    void confirmsTheEntryOnceTheElapsedPendingDurationReachesTheConfirmationThresholdEvenBelowTheReadingCount() {
        FenceMembershipConfig durationDriven = new FenceMembershipConfig(100, Duration.ofSeconds(30));
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(5), 1);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(35));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, durationDriven);

        assertThat(next.inside()).isTrue();
        assertThat(next.since()).isEqualTo(T0.plusSeconds(35));
        assertThat(next.pendingSince()).isNull();
    }

    @Test
    void discardsThePendingEntryWithoutEmittingAnythingWhenTheReadingRevertsToTheOriginalOutsideState() {
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(10), 1);
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0.plusSeconds(20));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isFalse();
        assertThat(next.since()).isEqualTo(T0);
        assertThat(next.pendingSince()).isNull();
        assertThat(next.pendingReadingCount()).isZero();
    }

    @Test
    void confirmsTheExitOnceTheBufferedBoundaryReadingStreakReachesTheConfirmationThreshold() {
        FenceMembershipState prev = new FenceMembershipState(true, T0, T0.plusSeconds(10), 2);
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0.plusSeconds(20));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isFalse();
        assertThat(next.since()).isEqualTo(T0.plusSeconds(20));
        assertThat(next.pendingSince()).isNull();
    }

    @Test
    void discardsThePendingExitWithoutEmittingAnythingWhenTheVehicleReturnsWithinTheBufferedBoundary() {
        FenceMembershipState prev = new FenceMembershipState(true, T0, T0.plusSeconds(10), 1);
        FenceMembershipSample sample = new FenceMembershipSample(false, true, T0.plusSeconds(20));

        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, CFG);

        assertThat(next.inside()).isTrue();
        assertThat(next.since()).isEqualTo(T0);
        assertThat(next.pendingSince()).isNull();
        assertThat(next.pendingReadingCount()).isZero();
    }

    @Test
    void aVehicleStoppedOnTheBoundaryWithRealisticGpsDriftNeverConfirmsATransition() {
        List<Boolean> strictBoundaryReadings = List.of(true, false, true, false, true, false);
        FenceMembershipState state = FenceMembershipState.confirmed(false, T0);
        Instant readingTime = T0;

        for (boolean insideStrict : strictBoundaryReadings) {
            readingTime = readingTime.plusSeconds(10);
            FenceMembershipSample sample = new FenceMembershipSample(insideStrict, true, readingTime);

            FenceMembershipState next = FenceMembershipDetector.next(state, sample, CFG);

            assertThat(FenceTransition.from(state.inside(), next.inside())).isEqualTo(FenceTransition.NONE);
            state = next;
        }

        assertThat(state.inside()).isFalse();
    }

    @Test
    void aRealEntryThatStaysSolidAfterAPeriodOfBoundaryOscillationConfirmsExactlyOnce() {
        List<Boolean> oscillatingReadings = List.of(true, false, true, false);
        FenceMembershipState state = FenceMembershipState.confirmed(false, T0);
        Instant readingTime = T0;
        int enteredCount = 0;

        for (boolean insideStrict : oscillatingReadings) {
            readingTime = readingTime.plusSeconds(10);
            FenceMembershipSample sample = new FenceMembershipSample(insideStrict, true, readingTime);
            FenceMembershipState next = FenceMembershipDetector.next(state, sample, CFG);

            if (FenceTransition.from(state.inside(), next.inside()) == FenceTransition.ENTERED) {
                enteredCount++;
            }
            state = next;
        }

        assertThat(state.inside()).isFalse();

        for (int i = 0; i < 3; i++) {
            readingTime = readingTime.plusSeconds(10);
            FenceMembershipSample sample = new FenceMembershipSample(true, true, readingTime);
            FenceMembershipState next = FenceMembershipDetector.next(state, sample, CFG);

            if (FenceTransition.from(state.inside(), next.inside()) == FenceTransition.ENTERED) {
                enteredCount++;
            }
            state = next;
        }

        assertThat(state.inside()).isTrue();
        assertThat(enteredCount).isEqualTo(1);
    }
}
