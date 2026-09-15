package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.geofencing.GeofenceRuleDispatcher;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
// consumer's schema or timing being decided here. `online` is owned
// exclusively by the presence consumer.
//
// Task 4.2: motion_state and the two streak-start columns (V7) are set in
// the SAME SET clause as location/recorded_at, guarded by the SAME WHERE --
// not a second guarded statement. VehicleMotionStreakTracker.computeUpdates()
// runs MotionDetector.next() in Java ahead of the write, seeded from
// whatever vehicle_state already holds for the vehicles in this batch
// (loadKnownMotionStates()); if the WHERE clause ends up rejecting a
// message as stale, the motion_state/streak values computed for it are
// simply discarded along with location/recorded_at -- this class does not
// duplicate the guard's comparison to decide whether to write, only to
// correctly chain multiple same-vehicle messages within one flush batch
// (VehicleMotionStreakTracker's own responsibility, see its class comment).
//
// Task 2.4 (WU4, tasks.md forecast's architecture decision): geofence
// evaluation is wired into this SAME guarded write path, not a separate
// consumer or a second guard. selectGeofenceEligibleMessages() below
// mirrors VehicleMotionStreakTracker's own isNewer()/running-map chaining
// exactly -- same comparison (message.recordedAt().isAfter(known)), same
// "no known state or null recordedAt counts as newer" rule -- computed from
// the SAME knownMotionStates snapshot already loaded for motion detection,
// so this costs zero extra queries. Deliberately NOT sourced from
// UPSERT_VEHICLE_STATE_SQL's own batchUpdate() return value (an int[] of
// per-statement affected-row counts is available there too): the JDBC
// driver is free to report Statement.SUCCESS_NO_INFO for a batched
// statement instead of an exact 0/1 count depending on driver/batching mode,
// which would silently misclassify an accepted write as stale. Mirroring
// VehicleMotionStreakTracker's already-proven comparison in Java sidesteps
// that risk entirely and stays consistent with "the same pattern task 4.2
// already established" the forecast calls for.
@Component
public class JdbcTelemetryPositionWriter implements TelemetryPositionWriter {

    private static final String INSERT_SQL = """
        INSERT INTO positions (vehicle_id, recorded_at, location, speed_kmh, heading, ignition)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)
        ON CONFLICT (vehicle_id, recorded_at) DO NOTHING
        """;

    private static final String SELECT_KNOWN_MOTION_STATES_SQL = """
        SELECT vehicle_id, recorded_at, motion_state, low_speed_streak_started_at, high_speed_streak_started_at
        FROM vehicle_state
        WHERE vehicle_id = ANY (?)
        """;

    private static final String UPSERT_VEHICLE_STATE_SQL = """
        INSERT INTO vehicle_state (
            vehicle_id, location, recorded_at, motion_state, low_speed_streak_started_at, high_speed_streak_started_at
        )
        VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?)
        ON CONFLICT (vehicle_id) DO UPDATE
        SET location = excluded.location,
            recorded_at = excluded.recorded_at,
            motion_state = excluded.motion_state,
            low_speed_streak_started_at = excluded.low_speed_streak_started_at,
            high_speed_streak_started_at = excluded.high_speed_streak_started_at
        WHERE vehicle_state.recorded_at IS NULL OR vehicle_state.recorded_at < excluded.recorded_at
        """;

    private final JdbcTemplate jdbcTemplate;
    private final VehicleMotionStreakTracker motionStreakTracker;
    private final GeofenceRuleDispatcher geofenceRuleDispatcher;

    public JdbcTelemetryPositionWriter(
        JdbcTemplate jdbcTemplate, VehicleMotionStreakTracker motionStreakTracker, GeofenceRuleDispatcher geofenceRuleDispatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.motionStreakTracker = motionStreakTracker;
        this.geofenceRuleDispatcher = geofenceRuleDispatcher;
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

        Map<UUID, VehicleMotionSnapshot> knownMotionStates = loadKnownMotionStates(messages);
        List<VehicleMotionUpdate> motionUpdates = motionStreakTracker.computeUpdates(knownMotionStates, messages);
        List<TelemetryMessage> geofenceEligibleMessages = selectGeofenceEligibleMessages(knownMotionStates, messages);

        jdbcTemplate.batchUpdate(UPSERT_VEHICLE_STATE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryMessage message = messages.get(i);
                VehicleMotionUpdate update = motionUpdates.get(i);
                ps.setObject(1, message.vehicleId());
                ps.setDouble(2, message.lon());
                ps.setDouble(3, message.lat());
                ps.setTimestamp(4, Timestamp.from(message.recordedAt()));
                setNullableMotionState(ps, 5, update.motionState());
                setNullableTimestamp(ps, 6, update.lowSpeedStreakStartedAt());
                setNullableTimestamp(ps, 7, update.highSpeedStreakStartedAt());
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        });

        geofenceRuleDispatcher.evaluateAndDispatch(geofenceEligibleMessages);
    }

    // See this class's own comment above for why this mirrors
    // VehicleMotionStreakTracker.isNewer()'s comparison instead of reading
    // UPSERT_VEHICLE_STATE_SQL's batchUpdate() return counts. running is
    // seeded from knownMotionStates (the state BEFORE this batch) and
    // chained forward per message, exactly like
    // VehicleMotionStreakTracker.computeUpdates()'s own running map, so a
    // second eligible message for the same vehicle within this batch is
    // correctly compared against the first message's recordedAt, not
    // against stale pre-batch state.
    private static List<TelemetryMessage> selectGeofenceEligibleMessages(
        Map<UUID, VehicleMotionSnapshot> knownMotionStates, List<TelemetryMessage> messages
    ) {
        Map<UUID, Instant> lastKnownRecordedAt = new HashMap<>();
        knownMotionStates.forEach((vehicleId, snapshot) -> lastKnownRecordedAt.put(vehicleId, snapshot.recordedAt()));

        List<TelemetryMessage> eligible = new ArrayList<>();
        for (TelemetryMessage message : messages) {
            Instant known = lastKnownRecordedAt.get(message.vehicleId());
            if (known == null || message.recordedAt().isAfter(known)) {
                eligible.add(message);
                lastKnownRecordedAt.put(message.vehicleId(), message.recordedAt());
            }
        }
        return eligible;
    }

    // One upfront lookup per flush batch for every distinct vehicle involved,
    // instead of a lookup per message: VehicleMotionStreakTracker then
    // chains any additional same-vehicle messages within the batch entirely
    // in Java (see its class comment).
    private Map<UUID, VehicleMotionSnapshot> loadKnownMotionStates(List<TelemetryMessage> messages) {
        List<UUID> vehicleIds = messages.stream().map(TelemetryMessage::vehicleId).distinct().toList();
        return jdbcTemplate.query(
            SELECT_KNOWN_MOTION_STATES_SQL,
            ps -> {
                Array vehicleIdArray = ps.getConnection().createArrayOf("uuid", vehicleIds.toArray());
                ps.setArray(1, vehicleIdArray);
            },
            rs -> {
                Map<UUID, VehicleMotionSnapshot> knownStates = new HashMap<>();
                while (rs.next()) {
                    knownStates.put((UUID) rs.getObject("vehicle_id"), toSnapshot(rs));
                }
                return knownStates;
            }
        );
    }

    private static VehicleMotionSnapshot toSnapshot(ResultSet rs) throws SQLException {
        Timestamp recordedAt = rs.getTimestamp("recorded_at");
        String motionState = rs.getString("motion_state");
        Timestamp lowStreakStartedAt = rs.getTimestamp("low_speed_streak_started_at");
        Timestamp highStreakStartedAt = rs.getTimestamp("high_speed_streak_started_at");
        return new VehicleMotionSnapshot(
            toInstant(recordedAt),
            motionState == null ? null : MotionState.valueOf(motionState),
            toInstant(lowStreakStartedAt),
            toInstant(highStreakStartedAt)
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setNullableMotionState(PreparedStatement ps, int index, MotionState value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.name());
        }
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
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
