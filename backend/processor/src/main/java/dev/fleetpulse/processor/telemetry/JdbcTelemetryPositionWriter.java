package dev.fleetpulse.processor.telemetry;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

// Task 3.2: JdbcTemplate.batchUpdate directly against `positions`, not JPA
// -- design.md is explicit that the persistence context's entity tracking
// is the wrong tool for high-volume append-only writes. ON CONFLICT DO
// NOTHING on the (vehicle_id, recorded_at) primary key is the deduplication
// mechanism itself (design.md): a resent point collides and is silently
// dropped by the database, not by an in-memory check a process restart
// would lose.
@Component
public class JdbcTelemetryPositionWriter implements TelemetryPositionWriter {

    private static final String INSERT_SQL = """
        INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, heading, ignition)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)
        ON CONFLICT (vehicle_id, recorded_at) DO NOTHING
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTelemetryPositionWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void writeBatch(List<TelemetryMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryMessage message = messages.get(i);
                ps.setObject(1, message.vehicleId());
                ps.setTimestamp(2, Timestamp.from(message.recordedAt()));
                // ST_MakePoint(x, y): geographic x is longitude, y is latitude.
                ps.setDouble(3, message.lon());
                ps.setDouble(4, message.lat());
                setNullableFloat(ps, 5, message.speedKmh());
                setNullableFloat(ps, 6, message.heading());
                setNullableBoolean(ps, 7, message.ignition());
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        });
    }

    private static void setNullableFloat(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, value.floatValue());
        }
    }

    private static void setNullableBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BOOLEAN);
        } else {
            ps.setBoolean(index, value);
        }
    }
}
