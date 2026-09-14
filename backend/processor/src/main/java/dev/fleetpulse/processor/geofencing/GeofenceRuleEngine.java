package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipConfig;
import dev.fleetpulse.geocore.FenceMembershipDetector;
import dev.fleetpulse.geocore.FenceMembershipSample;
import dev.fleetpulse.geocore.FenceMembershipState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// Task 4.1: turns geo-core's confirmed inside/outside hysteresis
// (FenceMembershipDetector, WU3) into the three product-level alert rules --
// geo-core has no concept of on_enter/on_exit/on_dwell, those are this
// project's domain semantics layered on top of the pure geometry decision,
// the same separation VehicleMotionStreakTracker already established
// between geo-core's MotionDetector and this module's own motion-streak
// bookkeeping.
//
// Pure and Spring-free like VehicleMotionStreakTracker's own computation
// methods, so it is unit-testable (GeofenceRuleEngineTest) without a
// database or broker: the dwell elapsed-time check is a plain
// Duration.between(next.since(), sample.observedAt()) comparison, not a
// second geo-core class, matching VehicleMotionStreakTracker's own
// precedent of computing durations from timestamps rather than storing
// elapsed time directly.
public final class GeofenceRuleEngine {

    private GeofenceRuleEngine() {
    }

    public static GeofenceRuleOutcome next(
        FenceMembershipState prev,
        boolean dwellAlerted,
        GeofenceRule rule,
        FenceMembershipSample sample,
        FenceMembershipConfig config
    ) {
        FenceMembershipState next = FenceMembershipDetector.next(prev, sample, config);
        List<GeofenceAlertType> alerts = new ArrayList<>();
        boolean nextDwellAlerted = dwellAlerted;

        // next.inside() != prev.inside() means FenceMembershipDetector just
        // confirmed a transition on THIS call (a still-pending transition
        // never changes .inside()) -- exactly the instant an on_enter/
        // on_exit alert must fire, never earlier (that would defeat WU3's
        // whole damping purpose) and never later.
        boolean justTransitioned = next.inside() != prev.inside();
        if (justTransitioned) {
            if (next.inside() && rule.type() == GeofenceRuleType.ON_ENTER) {
                alerts.add(GeofenceAlertType.ENTER);
            }
            if (!next.inside() && rule.type() == GeofenceRuleType.ON_EXIT) {
                alerts.add(GeofenceAlertType.EXIT);
            }
            // A fresh stay begins (or ends) the instant a confirmed
            // transition happens -- either way, any previous dwell alert no
            // longer describes the vehicle's CURRENT stay.
            nextDwellAlerted = false;
        }

        if (next.inside() && rule.type() == GeofenceRuleType.ON_DWELL && !nextDwellAlerted) {
            Duration dwelled = Duration.between(next.since(), sample.observedAt());
            if (dwelled.compareTo(Duration.ofSeconds(rule.dwellSecs())) >= 0) {
                alerts.add(GeofenceAlertType.DWELL);
                nextDwellAlerted = true;
            }
        }

        return new GeofenceRuleOutcome(next, nextDwellAlerted, List.copyOf(alerts));
    }
}
