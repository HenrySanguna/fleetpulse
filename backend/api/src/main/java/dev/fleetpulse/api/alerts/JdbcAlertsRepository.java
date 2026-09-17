package dev.fleetpulse.api.alerts;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Task 3.4: reads/writes the unified `alerts` table (V12, WU3) with plain
// JdbcTemplate -- no JPA entity, the same "no ORM for a table another module
// already owns with raw SQL" convention JdbcAlertWriter (processor) and this
// package's sibling repositories (JdbcGeofenceRepository, VehicleStateJdbcReader)
// all follow. ObjectProvider-wrapped for the same DataSource-less-profile
// reason as every other repository in this module.
//
// findFiltered() builds its WHERE clause dynamically (one AND fragment per
// non-null AlertFilter field) rather than a single fixed SQL string with
// "(? IS NULL OR col = ?)" branches per filter: with four independent
// optional filters, sixteen combinations, dynamic construction is more
// readable than one giant always-evaluated predicate and avoids the
// PostgreSQL array-type-inference footgun a nullable `= ANY(?)` bind
// otherwise needs for the multi-value `types` filter specifically. Every
// value is still a bind parameter (never string-concatenated), so this is
// not a SQL-injection relaxation, purely a readability/branching choice.
@Component
class JdbcAlertsRepository {

    private static final String SELECT_COLUMNS_SQL = """
        SELECT a.id, a.vehicle_id, v.label AS vehicle_label, a.alert_type, a.context, g.name AS context_label,
               a.occurred_at, a.acknowledged
        FROM alerts a
        JOIN vehicles v ON v.id = a.vehicle_id
        LEFT JOIN geofences g ON g.id = a.context
        """;

    private static final String SELECT_ONE_SQL = SELECT_COLUMNS_SQL + "WHERE a.id = ? AND a.organization_id = ?";

    private static final String UPDATE_ACKNOWLEDGE_SQL = """
        UPDATE alerts SET acknowledged = true WHERE id = ? AND organization_id = ?
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    JdbcAlertsRepository(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<AlertResponse> findFiltered(UUID organizationId, AlertFilter filter) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS_SQL).append("WHERE a.organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);

        if (filter.types() != null && !filter.types().isEmpty()) {
            sql.append(" AND a.alert_type IN (")
                .append(String.join(", ", filter.types().stream().map(type -> "?").toList()))
                .append(")");
            filter.types().forEach(type -> params.add(type.toDbValue()));
        }
        if (filter.vehicleId() != null) {
            sql.append(" AND a.vehicle_id = ?");
            params.add(filter.vehicleId());
        }
        if (filter.acknowledged() != null) {
            sql.append(" AND a.acknowledged = ?");
            params.add(filter.acknowledged());
        }
        if (filter.from() != null) {
            sql.append(" AND a.occurred_at >= ?");
            params.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND a.occurred_at <= ?");
            params.add(Timestamp.from(filter.to()));
        }
        sql.append(" ORDER BY a.occurred_at DESC");

        return jdbcTemplate.getObject().query(sql.toString(), (rs, rowNum) -> toResponse(rs), params.toArray());
    }

    boolean acknowledge(UUID id, UUID organizationId) {
        return jdbcTemplate.getObject().update(UPDATE_ACKNOWLEDGE_SQL, id, organizationId) > 0;
    }

    Optional<AlertResponse> findById(UUID id, UUID organizationId) {
        return jdbcTemplate.getObject().query(
            SELECT_ONE_SQL,
            ps -> {
                ps.setObject(1, id);
                ps.setObject(2, organizationId);
            },
            rs -> rs.next() ? Optional.of(toResponse(rs)) : Optional.empty()
        );
    }

    private static AlertResponse toResponse(ResultSet rs) throws SQLException {
        UUID context = (UUID) rs.getObject("context");
        Timestamp occurredAt = rs.getTimestamp("occurred_at");
        return new AlertResponse(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("vehicle_id"),
            rs.getString("vehicle_label"),
            rs.getString("alert_type"),
            context,
            context == null ? null : rs.getString("context_label"),
            occurredAt == null ? null : occurredAt.toInstant(),
            rs.getBoolean("acknowledged")
        );
    }
}
