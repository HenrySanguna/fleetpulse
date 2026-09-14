package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipState;

import java.util.List;

// GeofenceRuleEngine.next()'s result: the confirmed membership state to
// persist, the dwell_alerted flag to persist alongside it, and any alerts
// that just fired as a consequence -- empty when nothing crossed a rule
// threshold this message.
public record GeofenceRuleOutcome(FenceMembershipState nextState, boolean dwellAlerted, List<GeofenceAlertType> firedAlerts) {
}
