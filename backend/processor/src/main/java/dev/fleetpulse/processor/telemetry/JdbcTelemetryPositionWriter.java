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
//
// Task 4.1: every message that reaches `positions` also upserts
// `vehicle_state` -- but only advances it when strictly newer than what is
// stored, the monotonic guard from design.md ("Desorden: el estado actual
// no se puede sobrescribir a ciegas"). Lazy-upsert-vs-pre-seeded decision:
// `vehicle_state` rows are lazily upserted here, the first time a vehicle is
// ever seen by this writer, via a single `INSERT ... ON CONFLICT (vehicle_id)
// DO UPDATE ... WHERE` statement -- not pre-seeded by a separate process.
// V5's migration comment already anticipated a future presence consumer
// (change 03, "Presencia con LWT", not yet built) that could create a
// vehicle_state row from a testament with location/recorded_at still NULL
// before any telemetry ever arrives; the WHERE clause below treats a NULL
// stored recorded_at as "older than anything", so this upsert works
// correctly whichever side creates the row first, without the presence
// consumer's schema or timing being decided here. Only location/recorded_at
// are touched: `motion_state` is left to a later task (4.2, MotionDetector)
// and `online` is owned exclusively by the presence consumer.
@Component
public class JdbcTelemetryPositionWriter implements TelemetryPositionWriter {

    private static final String INSERT_SQL = """
        INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, heading, ignition)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)
        ON CONFLICT (vehicle_id, recorded_at) DO NOTHING
        """;

    private static final String UPSERT_VEHICLE_STATE_SQL = """
        INSERT INTO vehicle_state (vehicle_id, location, recorded_at)
        VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
        ON CONFLICT (vehicle_id) DO UPDATE
        SET location = excluded.location,
            recorded_at = excluded.recorded_at
        WHERE vehicle_state.recorded_at IS NULL OR vehicle_state.recorded_at < excluded.recorded_at
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
        jdbcTemplate.batchUpdate(UPSERT_VEHICLE_STATE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryMessage message = messages.get(i);
                ps.setObject(1, message.vehicleId());
                ps.setDouble(2, message.lon());
                ps.setDouble(3, message.lat());
                ps.setTimestamp(4, Timestamp.from(message.recordedAt()));
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
