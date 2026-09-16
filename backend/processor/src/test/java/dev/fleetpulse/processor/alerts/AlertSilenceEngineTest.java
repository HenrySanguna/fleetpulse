package dev.fleetpulse.processor.alerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Task 3.3: pure, database-free proof of AlertSilenceEngine's OR-semantics --
// the "focused test" half of this work unit's evidence, mirroring
// GeofenceRuleEngineTest's own style (explicit prev-state + condition + now
// in, decision out). Test 5.6's own "sustained speeding produces one alert,
// not one per position" claim is proven twice: here at the pure-decision
// level (manySustainedReadingsProduceExactlyOneFire), and again end to end
// through the real wired pipeline (AlertRuleEndToEndTest).
class AlertSilenceEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Test
    void firesOnAFreshEpisodeWithNoPriorState() {
        AlertSilenceDecision decision = AlertSilenceEngine.evaluate(AlertSilenceState.NONE, true, T0, WINDOW);

        assertThat(decision.shouldFire()).isTrue();
        assertThat(decision.nextState().active()).isTrue();
        assertThat(decision.nextState().lastAlertAt()).isEqualTo(T0);
    }

    @Test
    void doesNotFireAgainWhileTheConditionStaysActiveWithinTheSilenceWindow() {
        AlertSilenceState prev = new AlertSilenceState(true, T0);

        AlertSilenceDecision decision = AlertSilenceEngine.evaluate(prev, true, T0.plus(Duration.ofMinutes(5)), WINDOW);

        assertThat(decision.shouldFire()).isFalse();
        assertThat(decision.nextState().active()).isTrue();
        // lastAlertAt is unchanged -- the window's reference point stays the
        // ORIGINAL alert, not this suppressed message, so the window keeps
        // counting from when the dispatcher was actually last notified.
        assertThat(decision.nextState().lastAlertAt()).isEqualTo(T0);
    }

    @Test
    void manySustainedReadingsProduceExactlyOneFire() {
        AlertSilenceState state = AlertSilenceState.NONE;
        int fireCount = 0;
        Instant cursor = T0;
        for (int i = 0; i < 50; i++) {
            AlertSilenceDecision decision = AlertSilenceEngine.evaluate(state, true, cursor, WINDOW);
            if (decision.shouldFire()) {
                fireCount++;
            }
            state = decision.nextState();
            cursor = cursor.plusSeconds(10);
        }

        assertThat(fireCount).isEqualTo(1);
    }

    @Test
    void reFiresOnceTheSilenceWindowElapsesWhileTheConditionStaysActive() {
        AlertSilenceState prev = new AlertSilenceState(true, T0);

        AlertSilenceDecision decision = AlertSilenceEngine.evaluate(prev, true, T0.plus(WINDOW), WINDOW);

        assertThat(decision.shouldFire()).isTrue();
        assertThat(decision.nextState().lastAlertAt()).isEqualTo(T0.plus(WINDOW));
    }

    @Test
    void resolvingTheConditionResetsActiveButKeepsTheLastAlertTimestamp() {
        AlertSilenceState prev = new AlertSilenceState(true, T0);

        AlertSilenceDecision decision = AlertSilenceEngine.evaluate(prev, false, T0.plusSeconds(30), WINDOW);

        assertThat(decision.shouldFire()).isFalse();
        assertThat(decision.nextState().active()).isFalse();
        assertThat(decision.nextState().lastAlertAt()).isEqualTo(T0);
    }

    @Test
    void reFiresImmediatelyWhenTheConditionResolvesAndReoccursWellBeforeTheWindowElapses() {
        AlertSilenceState afterFirstAlert = new AlertSilenceState(true, T0);
        AlertSilenceDecision resolved = AlertSilenceEngine.evaluate(afterFirstAlert, false, T0.plusSeconds(30), WINDOW);

        // Well inside the 15-minute window, but the condition resolved
        // (asserted above) and is now active again: design.md's own "o la
        // condicion se resuelve y vuelve a darse" -- a fresh episode fires
        // regardless of the window.
        AlertSilenceDecision reoccurred = AlertSilenceEngine.evaluate(resolved.nextState(), true, T0.plusSeconds(60), WINDOW);

        assertThat(reoccurred.shouldFire()).isTrue();
        assertThat(reoccurred.nextState().lastAlertAt()).isEqualTo(T0.plusSeconds(60));
    }
}
