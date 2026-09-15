package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipState;

// One row of vehicle_fence_state, read back into geo-core's own state shape
// plus the one column (dwell_alerted) FenceMembershipState has no concept
// of -- geo-core's hysteresis decision has nothing to do with whether a
// dwell alert already fired for the current stay, that is purely a
// processor-side rule-dispatch concern (GeofenceRuleEngine).
public record GeofenceMembershipRecord(FenceMembershipState state, boolean dwellAlerted) {
}
