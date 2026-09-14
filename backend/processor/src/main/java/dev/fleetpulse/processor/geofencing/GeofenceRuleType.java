package dev.fleetpulse.processor.geofencing;

// Mirrors the three values geofences.rule is CHECK-constrained to (V8,
// chk_geofences_rule).
public enum GeofenceRuleType {
    ON_ENTER,
    ON_EXIT,
    ON_DWELL;

    public static GeofenceRuleType fromDbValue(String value) {
        return switch (value) {
            case "on_enter" -> ON_ENTER;
            case "on_exit" -> ON_EXIT;
            case "on_dwell" -> ON_DWELL;
            default -> throw new IllegalArgumentException("Unknown geofence rule: " + value);
        };
    }
}
