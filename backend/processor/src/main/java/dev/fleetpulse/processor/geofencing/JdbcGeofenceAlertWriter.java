package dev.fleetpulse.processor.geofencing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

// Task 4.3: persists a fired GeofenceAlert for the console's history/alerts
// panel. Plain JdbcTemplate insert, no JPA entity -- an append-only event log
// like `positions`, not a mutable aggregate.
//
// Retargeted at the unified `alerts` table by 06-add-trips-eta-alerts/WU3
// (task 3.1): this class's own external contract (its constructor, its
// write(GeofenceAlert) method, MqttGeofenceAlertPublisher's own unchanged
// payload) is deliberately untouched -- only the persistence target and the
// stored alert_type value (GeofenceAlertType.storageValue()'s "geofence_"
// prefix, matching AlertType's own naming) changed. geofenceId is stored in
// `alerts.context`, which keeps the exact same FOREIGN KEY REFERENCES
// geofences (id) geofence_alerts.geofence_id (V9) had (V12's own comment).
@Component
public class JdbcGeofenceAlertWriter {

    private static final String INSERT_SQL = """
        INSERT INTO alerts (id, organization_id, vehicle_id, alert_type, context, occurred_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGeofenceAlertWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(GeofenceAlert alert) {
        jdbcTemplate.update(INSERT_SQL, ps -> {
            ps.setObject(1, alert.id());
            ps.setObject(2, alert.organizationId());
            ps.setObject(3, alert.vehicleId());
            ps.setString(4, alert.type().storageValue());
            ps.setObject(5, alert.geofenceId());
            ps.setTimestamp(6, Timestamp.from(alert.occurredAt()));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
        });
    }
}
