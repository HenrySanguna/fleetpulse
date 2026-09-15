package dev.fleetpulse.processor.geofencing;

// dwellSecs is only ever non-null when type is ON_DWELL -- the same
// conditional relevance chk_geofences_dwell_secs (V8) enforces in the
// database, enforced again here so a caller cannot construct an
// inconsistent rule from a mis-read row.
public record GeofenceRule(GeofenceRuleType type, Integer dwellSecs) {

    public GeofenceRule {
        if (type == GeofenceRuleType.ON_DWELL && dwellSecs == null) {
            throw new IllegalArgumentException("dwellSecs is required for ON_DWELL rules");
        }
        if (type != GeofenceRuleType.ON_DWELL && dwellSecs != null) {
            throw new IllegalArgumentException("dwellSecs must be null for non-ON_DWELL rules: " + type);
        }
    }
}
