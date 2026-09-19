package dev.fleetpulse.processor.alerts;

import java.time.Duration;
import java.time.Instant;

// Task 3.3 ("ventana de silencio por vehiculo, tipo y contexto"): design.md's
// own OR-worded rule -- "no se emite otra igual hasta que pasa la ventana O
// la condicion se resuelve y vuelve a darse" -- decomposes into two
// independent ways to become eligible to alert again: (a) the condition was
// NOT active last time and is active now (a fresh episode -- always fires,
// the "se resuelve y vuelve a darse" half, exactly spec.md's "Nueva alerta
// tras resolverse la condicion" scenario), or (b) the condition has stayed
// continuously active and the silence window has elapsed since the last
// alert (a periodic re-notification for a still-ongoing episode -- the
// "pasa la ventana" half, exactly spec.md's "Exceso de velocidad sostenido"
// scenario when the window comfortably outlasts the test's own duration).
// Pure and Spring-free, the same GeofenceRuleEngine/FenceMembershipDetector
// shape this project already established for damping logic, so it is
// unit-testable (AlertSilenceEngineTest) without a database.
//
// Whenever the condition is NOT active this message, the state resets to
// inactive (preserving lastAlertAt only as a reference point) so the NEXT
// occurrence -- whenever it happens -- is always treated as a fresh episode.
public final class AlertSilenceEngine {

    private AlertSilenceEngine() {
    }

    public static AlertSilenceDecision evaluate(AlertSilenceState prev, boolean conditionActive, Instant now, Duration silenceWindow) {
        if (!conditionActive) {
            return new AlertSilenceDecision(false, new AlertSilenceState(false, prev.lastAlertAt()));
        }

        boolean freshEpisode = !prev.active();
        boolean windowElapsed = prev.lastAlertAt() == null
            || Duration.between(prev.lastAlertAt(), now).compareTo(silenceWindow) >= 0;
        boolean shouldFire = freshEpisode || windowElapsed;

        Instant nextLastAlertAt = shouldFire ? now : prev.lastAlertAt();
        return new AlertSilenceDecision(shouldFire, new AlertSilenceState(true, nextLastAlertAt));
    }
}
