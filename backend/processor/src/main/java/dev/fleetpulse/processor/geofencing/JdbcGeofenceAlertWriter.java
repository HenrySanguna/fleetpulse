package dev.fleetpulse.processor.geofencing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

// Task 4.3: persists a fired GeofenceAlert for the console's history/alerts
// panel. Plain JdbcTemplate insert, no JPA entity -- geofence_alerts is an
// append-only event log like `positions`, not a mutable aggregate.
@Component
public class JdbcGeofenceAlertWriter {

    private static final String INSERT_SQL = """
        INSERT INTO geofence_alerts (id, organization_id, vehicle_id, geofence_id, alert_type, occurred_at, created_at)
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
            ps.setObject(4, alert.geofenceId());
            ps.setString(5, alert.type().wireValue());
            ps.setTimestamp(6, Timestamp.from(alert.occurredAt()));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
        });
    }
}
