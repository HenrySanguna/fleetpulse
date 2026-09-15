package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipConfig;
import dev.fleetpulse.geocore.FenceMembershipSample;
import dev.fleetpulse.geocore.FenceMembershipState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Task 4.1: pure, database-free proof that GeofenceRuleEngine turns
// geo-core's confirmed hysteresis (FenceMembershipDetector, WU3) into
// exactly the right on_enter/on_exit/on_dwell alerts -- the "focused test"
// half of this work unit's evidence, mirroring FenceMembershipDetectorTest's
// own style (explicit prev-state + sample + config in, outcome out).
class GeofenceRuleEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final FenceMembershipConfig CFG = new FenceMembershipConfig(3, Duration.ofSeconds(60));
    private static final GeofenceRule ON_ENTER = new GeofenceRule(GeofenceRuleType.ON_ENTER, null);
    private static final GeofenceRule ON_EXIT = new GeofenceRule(GeofenceRuleType.ON_EXIT, null);
    private static final GeofenceRule ON_DWELL_300 = new GeofenceRule(GeofenceRuleType.ON_DWELL, 300);

    @Test
    void firesAnEnterAlertTheInstantTheEntryIsConfirmed() {
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(10), 2);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(20));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_ENTER, sample, CFG);

        assertThat(outcome.nextState().inside()).isTrue();
        assertThat(outcome.firedAlerts()).containsExactly(GeofenceAlertType.ENTER);
    }

    @Test
    void firesNoAlertWhenAnEntryConfirmsButTheRuleIsOnExit() {
        FenceMembershipState prev = new FenceMembershipState(false, T0, T0.plusSeconds(10), 2);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(20));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_EXIT, sample, CFG);

        assertThat(outcome.nextState().inside()).isTrue();
        assertThat(outcome.firedAlerts()).isEmpty();
    }

    @Test
    void firesAnExitAlertTheInstantTheExitIsConfirmed() {
        FenceMembershipState prev = new FenceMembershipState(true, T0, T0.plusSeconds(10), 2);
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0.plusSeconds(20));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_EXIT, sample, CFG);

        assertThat(outcome.nextState().inside()).isFalse();
        assertThat(outcome.firedAlerts()).containsExactly(GeofenceAlertType.EXIT);
    }

    @Test
    void firesNoAlertWhileATransitionIsStillOnlyPending() {
        FenceMembershipState prev = FenceMembershipState.confirmed(false, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(10));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_ENTER, sample, CFG);

        assertThat(outcome.nextState().inside()).isFalse();
        assertThat(outcome.nextState().pendingSince()).isNotNull();
        assertThat(outcome.firedAlerts()).isEmpty();
    }

    @Test
    void firesNoDwellAlertBeforeTheThresholdIsReached() {
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(299));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_DWELL_300, sample, CFG);

        assertThat(outcome.firedAlerts()).isEmpty();
        assertThat(outcome.dwellAlerted()).isFalse();
    }

    @Test
    void firesADwellAlertExactlyWhenTheThresholdIsReached() {
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(300));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_DWELL_300, sample, CFG);

        assertThat(outcome.firedAlerts()).containsExactly(GeofenceAlertType.DWELL);
        assertThat(outcome.dwellAlerted()).isTrue();
    }

    @Test
    void doesNotFireASecondDwellAlertWhileTheVehicleRemainsInsideWithoutLeaving() {
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(600));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, true, ON_DWELL_300, sample, CFG);

        assertThat(outcome.firedAlerts()).isEmpty();
        assertThat(outcome.dwellAlerted()).isTrue();
    }

    @Test
    void resetsDwellAlertedOnceAConfirmedExitEndsTheStay() {
        FenceMembershipState prev = new FenceMembershipState(true, T0, T0.plusSeconds(590), 2);
        FenceMembershipSample sample = new FenceMembershipSample(false, false, T0.plusSeconds(600));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, true, ON_DWELL_300, sample, CFG);

        assertThat(outcome.nextState().inside()).isFalse();
        assertThat(outcome.dwellAlerted()).isFalse();
        assertThat(outcome.firedAlerts()).isEmpty();
    }

    @Test
    void aFreshEntryCanDwellAlertAgainAfterAPreviousStayAlreadyDwellAlerted() {
        // A confirmed re-entry: prev.since() is the instant this new stay
        // began (the exit/re-entry cycle already happened), dwellAlerted
        // carried over false from the exit-resets-it case above.
        FenceMembershipState prev = FenceMembershipState.confirmed(true, T0);
        FenceMembershipSample sample = new FenceMembershipSample(true, true, T0.plusSeconds(300));

        GeofenceRuleOutcome outcome = GeofenceRuleEngine.next(prev, false, ON_DWELL_300, sample, CFG);

        assertThat(outcome.firedAlerts()).containsExactly(GeofenceAlertType.DWELL);
    }
}
