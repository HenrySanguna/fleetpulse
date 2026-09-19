package dev.fleetpulse.processor.alerts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

// Tasks 3.1/3.2: persists a fired Alert (any type) to the unified `alerts`
// table (V12) -- plain JdbcTemplate insert, no JPA entity, the same
// "append-only event log" convention JdbcGeofenceAlertWriter already
// established for its own (now-retargeted) writes into this exact table.
@Component
public class JdbcAlertWriter {

    private static final String INSERT_SQL = """
        INSERT INTO alerts (id, organization_id, vehicle_id, alert_type, context, occurred_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAlertWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(Alert alert) {
        jdbcTemplate.update(INSERT_SQL, ps -> {
            ps.setObject(1, alert.id());
            ps.setObject(2, alert.organizationId());
            ps.setObject(3, alert.vehicleId());
            ps.setString(4, alert.type().wireValue());
            setNullableUuid(ps, 5, alert.context());
            ps.setTimestamp(6, Timestamp.from(alert.occurredAt()));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
        });
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }
}
