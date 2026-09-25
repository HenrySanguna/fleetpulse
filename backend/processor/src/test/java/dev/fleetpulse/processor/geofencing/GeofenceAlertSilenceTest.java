package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.processor.alerts.AlertSilenceDecision;
import dev.fleetpulse.processor.alerts.AlertSilenceEngine;
import dev.fleetpulse.processor.alerts.AlertSilenceState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// T8 (prod-qa-findings, "geofence alert silence window"): pure proof of the
// EVENT-shaped usage GeofenceRuleDispatcher.dispatchForGeofence makes of the
// SAME AlertSilenceEngine speeding/excessive-idle already use -- see that
// class's own comment for why conditionActive is always true here, never
// false. AlertSilenceEngineTest already proves the engine's own OR-semantics
// generically; this proves the specific decision an oscillating geofence
// (repeated confirmed ENTER/EXIT within the window) produces when the caller
// only ever samples it at the instant an alert fires.
class GeofenceAlertSilenceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Test
    void theFirstFiredAlertOfAKeyAlwaysGoesThrough() {
        AlertSilenceDecision decision = AlertSilenceEngine.evaluate(AlertSilenceState.NONE, true, T0, WINDOW);

        assertThat(decision.shouldFire()).isTrue();
    }

    @Test
    void anOscillationReEnteringWithinTheWindowIsSuppressed() {
        AlertSilenceState state = AlertSilenceState.NONE;
        AlertSilenceDecision firstEnter = AlertSilenceEngine.evaluate(state, true, T0, WINDOW);
        state = firstEnter.nextState();

        // The vehicle oscillates: ENTER, EXIT, ENTER, EXIT... within minutes,
        // each one a real confirmed transition GeofenceRuleEngine fired.
        // Only the FIRST of these repeated ENTER events is ever notified.
        AlertSilenceDecision secondEnter = AlertSilenceEngine.evaluate(state, true, T0.plus(Duration.ofMinutes(3)), WINDOW);
        state = secondEnter.nextState();
        AlertSilenceDecision thirdEnter = AlertSilenceEngine.evaluate(state, true, T0.plus(Duration.ofMinutes(6)), WINDOW);

        assertThat(firstEnter.shouldFire()).isTrue();
        assertThat(secondEnter.shouldFire()).isFalse();
        assertThat(thirdEnter.shouldFire()).isFalse();
        // The window's reference point stays the original alert, exactly
        // AlertSilenceEngineTest's own "suppressed message never resets the
        // clock" semantics -- so a burst of oscillation never keeps pushing
        // re-eligibility further into the future.
        assertThat(thirdEnter.nextState().lastAlertAt()).isEqualTo(T0);
    }

    @Test
    void reEntryAfterTheWindowElapsesFiresANewAlert() {
        AlertSilenceState state = AlertSilenceEngine.evaluate(AlertSilenceState.NONE, true, T0, WINDOW).nextState();

        AlertSilenceDecision suppressed = AlertSilenceEngine.evaluate(state, true, T0.plus(Duration.ofMinutes(5)), WINDOW);
        AlertSilenceDecision afterWindow = AlertSilenceEngine.evaluate(suppressed.nextState(), true, T0.plus(WINDOW), WINDOW);

        assertThat(suppressed.shouldFire()).isFalse();
        assertThat(afterWindow.shouldFire()).isTrue();
        assertThat(afterWindow.nextState().lastAlertAt()).isEqualTo(T0.plus(WINDOW));
    }

    @Test
    void manyOscillationsWithinTheWindowProduceExactlyOneFire() {
        AlertSilenceState state = AlertSilenceState.NONE;
        Instant cursor = T0;
        int fireCount = 0;
        // 20 alternating ENTER/EXIT-shaped events, all well inside the
        // window: the flood this work unit exists to stop.
        for (int i = 0; i < 20; i++) {
            AlertSilenceDecision decision = AlertSilenceEngine.evaluate(state, true, cursor, WINDOW);
            if (decision.shouldFire()) {
                fireCount++;
            }
            state = decision.nextState();
            cursor = cursor.plusSeconds(20);
        }

        assertThat(fireCount).isEqualTo(1);
    }
}
