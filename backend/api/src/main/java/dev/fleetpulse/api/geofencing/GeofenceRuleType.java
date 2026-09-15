package dev.fleetpulse.api.geofencing;

// Mirrors dev.fleetpulse.processor.geofencing.GeofenceRuleType (WU4) --
// duplicated locally rather than shared, since api cannot depend on
// processor (module boundary rule; VehicleStateJdbcReader's own comment
// documents the same constraint for the JDBC-reader pattern this package
// also follows). Matches the three values geofences.rule is CHECK-constrained
// to (V8, chk_geofences_rule).
public enum GeofenceRuleType {
    ON_ENTER,
    ON_EXIT,
    ON_DWELL;

    public static GeofenceRuleType fromDbValue(String value) {
        return switch (value) {
            case "on_enter" -> ON_ENTER;
            case "on_exit" -> ON_EXIT;
            case "on_dwell" -> ON_DWELL;
            default -> throw new IllegalStateException("Unknown geofence rule in database: " + value);
        };
    }

    public String toDbValue() {
        return switch (this) {
            case ON_ENTER -> "on_enter";
            case ON_EXIT -> "on_exit";
            case ON_DWELL -> "on_dwell";
        };
    }
}
