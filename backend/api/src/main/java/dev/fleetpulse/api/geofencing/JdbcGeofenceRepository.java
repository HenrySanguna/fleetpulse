package dev.fleetpulse.api.geofencing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Task 5.2 (backend half, WU6 -- the gap documented in tasks.md's Review
// Workload Forecast). No JPA entity for `geofences`: `area` is a PostGIS
// GEOGRAPHY(Polygon, 4326) column, the same reason vehicle_state/positions/
// alerts are all read/written with plain JdbcTemplate instead of
// Hibernate throughout this codebase (VehicleStateJdbcReader's own comment
// documents the identical rule; GeofenceEvaluator, processor module,
// establishes the same convention on the read side of this exact table).
// ObjectProvider-wrapped for the same reason as VehicleStateJdbcReader: some
// deployment profiles boot the api module with no DataSource at all.
@Component
class JdbcGeofenceRepository {

    private static final String INSERT_POLYGON_SQL = """
        INSERT INTO geofences (id, organization_id, name, area, rule, dwell_secs, is_active, created_at)
        VALUES (?, ?, ?, ST_GeomFromText(?, 4326)::geography, ?, ?, true, ?)
        """;

    // ST_Buffer on a GEOGRAPHY point takes its distance argument in meters,
    // exactly matching radiusMeters -- no degree conversion needed, unlike
    // buffering in the planar `geometry` type would require. Always
    // produces a valid, simple polygon by construction, so (unlike the
    // polygon path) no separate ST_IsValid/ST_IsSimple check is needed here.
    private static final String INSERT_CIRCLE_SQL = """
        INSERT INTO geofences (id, organization_id, name, area, rule, dwell_secs, is_active, created_at)
        VALUES (?, ?, ?, ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?), ?, ?, true, ?)
        """;

    // is_active = true in the WHERE clause, not just the SET list: an
    // already soft-deleted (delete()'d) geofence is treated as gone for
    // update too, matching ordinary REST DELETE semantics -- a caller
    // cannot resurrect a deleted geofence by PUTting to its old id.
    private static final String UPDATE_POLYGON_SQL = """
        UPDATE geofences SET name = ?, area = ST_GeomFromText(?, 4326)::geography, rule = ?, dwell_secs = ?
        WHERE id = ? AND organization_id = ? AND is_active = true
        """;

    private static final String UPDATE_CIRCLE_SQL = """
        UPDATE geofences SET name = ?, area = ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?), rule = ?, dwell_secs = ?
        WHERE id = ? AND organization_id = ? AND is_active = true
        """;

    // DELETE /api/geofences/{id} resolves to THIS, not a hard SQL DELETE.
    // Deviation from a literal reading of "DELETE", documented per the
    // established "note deviations" convention: vehicle_fence_state.geofence_id
    // (V8) and alerts.context (V12, 06-add-trips-eta-alerts/WU3 -- formerly
    // geofence_alerts.geofence_id, V9, before that table was retired and
    // migrated into the unified `alerts` table) both REFERENCE geofences(id)
    // with no ON DELETE CASCADE anywhere in this schema -- a hard delete
    // would throw a foreign key violation the instant any vehicle has ever
    // been evaluated against the geofence, and cascading the delete would
    // silently destroy alerts' "histórico" (task 4.3's own stated purpose)
    // the same way this codebase never discards positions either.
    // `is_active` already exists in the schema (design.md's own sketch) for
    // exactly this purpose, and GeofenceEvaluator's containment query
    // already filters `AND is_active` (task 2.1) -- soft-deleting via this
    // column immediately and correctly stops the geofence from being
    // evaluated, which is the functional meaning "delete" needs for the
    // live pipeline, without ever violating referential integrity or losing
    // alert history.
    private static final String SOFT_DELETE_SQL = """
        UPDATE geofences SET is_active = false WHERE id = ? AND organization_id = ? AND is_active = true
        """;

    private static final String SELECT_METADATA_ONE_SQL = """
        SELECT id, name, rule, dwell_secs, created_at FROM geofences
        WHERE id = ? AND organization_id = ? AND is_active = true
        """;

    private static final String SELECT_METADATA_ALL_SQL = """
        SELECT id, name, rule, dwell_secs, created_at FROM geofences
        WHERE organization_id = ? AND is_active = true
        ORDER BY name
        """;

    // ST_DumpPoints -> (path integer[], geom geometry): for a Polygon with
    // no interior rings (this schema never has holes), path[1] is always the
    // (single) exterior ring and path[2] is the vertex's position within it
    // -- ordering by it reconstructs the ring exactly as PostGIS stored it,
    // including the repeated closing vertex. Bounded by `id = ANY(?)` (the
    // handful of geofences findById/findAllByOrganization already resolved
    // via SELECT_METADATA_*), not re-scanning the whole org, mirroring
    // GeofenceEvaluator's own id = ANY(?) pattern (LOAD_RULES_SQL,
    // BUFFERED_CONTAINMENT_AMONG_SQL). Kept as a second query rather than a
    // single array_agg'd join: avoids depending on the JDBC driver's
    // SQL-array-to-Java-array type mapping for `double precision[]`, mapping
    // each point through the same plain RowMapper style already proven
    // throughout this codebase instead.
    private static final String SELECT_VERTICES_SQL = """
        SELECT g.id AS geofence_id, ST_Y(dp.geom) AS lat, ST_X(dp.geom) AS lon
        FROM geofences g, LATERAL ST_DumpPoints(g.area::geometry) dp
        WHERE g.id = ANY (?)
        ORDER BY g.id, (dp.path)[2]
        """;

    // Task 5.2: run BEFORE the insert/update so a self-intersecting or
    // otherwise invalid ring ("self-intersecting polygon", launch prompt's
    // required test case) is rejected with 400 before ever reaching the
    // geofences table -- ST_GeomFromText itself separately rejects a
    // structurally malformed WKT string (caught by the DataAccessException
    // handling in insert()/update() below), but a self-intersecting ring IS
    // well-formed WKT, so it needs its own explicit predicate. ST_IsValid is
    // the operative check for a Polygon (self-intersection makes a polygon
    // INVALID; ST_IsSimple is primarily meaningful for linear geometries,
    // and is near-always true for any geometry ST_IsValid already accepts) --
    // ST_IsSimple is still included here alongside it, per the launch
    // prompt's explicit "ST_IsValid/ST_IsSimple" wording, as defense in
    // depth rather than the operative rejection predicate.
    private static final String VALIDATE_POLYGON_SQL = """
        SELECT ST_IsValid(g) AS is_valid, ST_IsSimple(g) AS is_simple, ST_IsValidReason(g) AS reason
        FROM (SELECT ST_GeomFromText(?, 4326) AS g) t
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    JdbcGeofenceRepository(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    UUID insert(UUID organizationId, GeofenceRequest request) {
        UUID id = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(Instant.now());
        JdbcTemplate template = jdbcTemplate.getObject();
        try {
            if (request.shape() == GeofenceShapeType.POLYGON) {
                String wkt = closedPolygonWkt(request.vertices());
                validatePolygonOrThrow(template, wkt);
                template.update(INSERT_POLYGON_SQL,
                    id, organizationId, request.name(), wkt,
                    request.rule().toDbValue(), request.dwellSecs(), createdAt);
            } else {
                GeoPointRequest center = request.center();
                template.update(INSERT_CIRCLE_SQL,
                    id, organizationId, request.name(), center.lon(), center.lat(), request.radiusMeters(),
                    request.rule().toDbValue(), request.dwellSecs(), createdAt);
            }
        } catch (DataAccessException e) {
            throw invalidGeometry(e);
        }
        return id;
    }

    boolean update(UUID id, UUID organizationId, GeofenceRequest request) {
        JdbcTemplate template = jdbcTemplate.getObject();
        int updated;
        try {
            if (request.shape() == GeofenceShapeType.POLYGON) {
                String wkt = closedPolygonWkt(request.vertices());
                validatePolygonOrThrow(template, wkt);
                updated = template.update(UPDATE_POLYGON_SQL,
                    request.name(), wkt, request.rule().toDbValue(), request.dwellSecs(), id, organizationId);
            } else {
                GeoPointRequest center = request.center();
                updated = template.update(UPDATE_CIRCLE_SQL,
                    request.name(), center.lon(), center.lat(), request.radiusMeters(),
                    request.rule().toDbValue(), request.dwellSecs(), id, organizationId);
            }
        } catch (DataAccessException e) {
            throw invalidGeometry(e);
        }
        return updated > 0;
    }

    boolean softDelete(UUID id, UUID organizationId) {
        return jdbcTemplate.getObject().update(SOFT_DELETE_SQL, id, organizationId) > 0;
    }

    Optional<GeofenceResponse> findById(UUID id, UUID organizationId) {
        JdbcTemplate template = jdbcTemplate.getObject();
        List<GeofenceMetadata> rows = template.query(
            SELECT_METADATA_ONE_SQL,
            ps -> {
                ps.setObject(1, id);
                ps.setObject(2, organizationId);
            },
            (rs, rowNum) -> new GeofenceMetadata(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                GeofenceRuleType.fromDbValue(rs.getString("rule")),
                (Integer) rs.getObject("dwell_secs"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, List<GeoPointResponse>> vertices = verticesFor(template, List.of(id));
        return Optional.of(toResponse(rows.get(0), vertices));
    }

    List<GeofenceResponse> findAllByOrganization(UUID organizationId) {
        JdbcTemplate template = jdbcTemplate.getObject();
        List<GeofenceMetadata> rows = template.query(
            SELECT_METADATA_ALL_SQL,
            ps -> ps.setObject(1, organizationId),
            (rs, rowNum) -> new GeofenceMetadata(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                GeofenceRuleType.fromDbValue(rs.getString("rule")),
                (Integer) rs.getObject("dwell_secs"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(GeofenceMetadata::id).toList();
        Map<UUID, List<GeoPointResponse>> vertices = verticesFor(template, ids);
        return rows.stream().map(row -> toResponse(row, vertices)).toList();
    }

    private Map<UUID, List<GeoPointResponse>> verticesFor(JdbcTemplate template, List<UUID> ids) {
        return template.query(
            SELECT_VERTICES_SQL,
            ps -> {
                java.sql.Array idArray = ps.getConnection().createArrayOf("uuid", ids.toArray());
                ps.setArray(1, idArray);
            },
            rs -> {
                Map<UUID, List<GeoPointResponse>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    UUID geofenceId = (UUID) rs.getObject("geofence_id");
                    result
                        .computeIfAbsent(geofenceId, key -> new ArrayList<>())
                        .add(new GeoPointResponse(rs.getDouble("lat"), rs.getDouble("lon")));
                }
                return result;
            }
        );
    }

    private static GeofenceResponse toResponse(GeofenceMetadata row, Map<UUID, List<GeoPointResponse>> vertices) {
        return new GeofenceResponse(
            row.id(), row.name(), row.rule(), row.dwellSecs(),
            vertices.getOrDefault(row.id(), List.of()), row.createdAt()
        );
    }

    private static void validatePolygonOrThrow(JdbcTemplate template, String wkt) {
        template.query(VALIDATE_POLYGON_SQL, ps -> ps.setString(1, wkt), rs -> {
            if (rs.next()) {
                boolean valid = rs.getBoolean("is_valid");
                boolean simple = rs.getBoolean("is_simple");
                if (!valid || !simple) {
                    String reason = rs.getString("reason");
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid polygon geometry: " + (reason != null ? reason : "self-intersecting or malformed ring"));
                }
            }
            return null;
        });
    }

    // Task 1.3: a well-formed WKT Polygon ring must be CLOSED (first vertex
    // repeated as the last) -- guaranteed here by the server rather than
    // required of the caller, so "the polygon must be closed" is a
    // structural invariant this method always produces, not a client input
    // that can be wrong. Self-intersection is what VALIDATE_POLYGON_SQL
    // actually rejects, not this method.
    private static String closedPolygonWkt(List<GeoPointRequest> vertices) {
        StringBuilder ring = new StringBuilder("POLYGON((");
        for (GeoPointRequest vertex : vertices) {
            ring.append(vertex.lon()).append(' ').append(vertex.lat()).append(", ");
        }
        GeoPointRequest first = vertices.get(0);
        ring.append(first.lon()).append(' ').append(first.lat()).append("))");
        return ring.toString();
    }

    private static ResponseStatusException invalidGeometry(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Invalid geofence geometry: " + (cause != null ? cause.getMessage() : e.getMessage()));
    }

    private record GeofenceMetadata(UUID id, String name, GeofenceRuleType rule, Integer dwellSecs, Instant createdAt) {
    }
}
