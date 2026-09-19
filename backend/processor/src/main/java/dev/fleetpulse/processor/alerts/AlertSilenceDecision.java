package dev.fleetpulse.processor.alerts;

// AlertSilenceEngine.evaluate()'s result: whether to fire a new alert for
// this message, and the silence state to persist for the next one.
public record AlertSilenceDecision(boolean shouldFire, AlertSilenceState nextState) {
}
