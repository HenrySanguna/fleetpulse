package dev.fleetpulse.api.alerts;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

// Task 3.4: mirrors dev.fleetpulse.processor.alerts.AlertType (V12's own
// chk_alerts_alert_type values) -- duplicated locally rather than shared,
// matching GeofenceRuleType's own precedent (api cannot depend on processor,
// module boundary rule). toDbValue() follows the exact same
// name().toLowerCase(Locale.ROOT) shape as processor's own
// AlertType.wireValue(), since alert_type's wire vocabulary is lowercase
// end to end (DB storage, AlertPayload's own MQTT `type` field) -- unlike
// GeofenceRuleType/MotionState, which have no equivalent lowercase MQTT/DB
// precedent to match, alerts.alert_type does, so AlertResponse.alertType
// (see AlertResponse) is typed String and carries this same lowercase value
// rather than the Java enum's own uppercase name(), keeping one wire
// vocabulary for "alert type" across MQTT and HTTP instead of introducing a
// third, inconsistent casing.
public enum AlertType {
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    GEOFENCE_DWELL,
    SPEEDING,
    EXCESSIVE_IDLE,
    OFFLINE;

    // Query-param filter parsing (GET /api/alerts?type=...): a whitelist
    // against the lowercase wire vocabulary, rejecting an unknown value with
    // 400 rather than silently matching zero rows -- the untrusted-input
    // counterpart to GeofenceRuleType.fromDbValue()'s IllegalStateException,
    // which is only ever reached for already-trusted, already-CHECK-constrained
    // database content.
    public static AlertType fromQueryValue(String value) {
        for (AlertType type : values()) {
            if (type.toDbValue().equals(value)) {
                return type;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown alert type: " + value);
    }

    public String toDbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
