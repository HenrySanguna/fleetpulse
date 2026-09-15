package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceTransition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Tasks 2.1-2.3 (design.md "Evaluacion: una consulta, no N"): a single
// native spatial query -- GiST-indexed via idx_geofences_area (V8) -- returns
// every active geofence in the vehicle's organization that currently
// contains the point, instead of looping over geofences one by one and
// running N separate containment checks (test 6.9 proves the index is used,
// not a sequential scan). The resulting set is compared against
// vehicle_fence_state's previously-inside set (design.md: "una operacion de
// conjuntos, no un bucle de consultas"), and geo-core's FenceTransition.from()
// -- already idle since change 01-add-geo-core, zero new geo-core code
// needed here -- makes the pure ENTERED/EXITED/NONE decision per geofence
// (test 6.5 proves two overlapping geofences both surface their own
// transition).
//
// This class is deliberately standalone and read-only against
// vehicle_fence_state: it neither writes is_inside/since/pending_since nor
// applies oscillation damping. Persisting the confirmed transition (after
// WU3's temporal-confirmation/asymmetric-buffer damping is applied) is WU4's
// concern, wired into JdbcTelemetryPositionWriter's existing guarded write
// path per the task-2.4 architecture decision (tasks.md forecast). A
// geofence that was previously inside but has since been deactivated
// (is_active = false) is excluded from the containment query and therefore
// still reports EXITED here: the vehicle is no longer inside any geofence a
// caller should track, deactivated or not, and leaving a stale
// is_inside = true row behind would silently hide that.
@Component
public class GeofenceEvaluator {

    // Task 2.1: package-private (not private) so GeofenceEvaluatorTest's
    // GiST execution-plan assertion (test 6.9) proves this EXACT SQL uses
    // the index, instead of a similar-looking copy that could drift from it.
    //
    // Deviates from design.md's SQL sketch (ST_Contains(area::geometry, ...))
    // in one deliberate way, documented per the "note deviations" convention:
    // idx_geofences_area (V8) is a GiST index built directly on the
    // `geography` column, using PostGIS' geography opclass. Casting
    // area::geometry before calling ST_Contains produces a per-row computed
    // geometry value the geography index cannot support at all -- the
    // planner then has no usable access path and falls back to a sequential
    // scan regardless of the index's existence (confirmed empirically: test
    // 6.9 failed with a Seq Scan until this fix). ST_Covers(geography,
    // geography) is natively index-aware against a geography GiST index, and
    // is one of the two predicates task 2.1 explicitly allows. The only
    // behavioral difference from ST_Contains is boundary inclusion (a point
    // exactly on the polygon's edge counts as covered); acceptable here
    // since the boundary-oscillation case is what WU3's damping exists to
    // absorb regardless of which side of the line a single noisy reading
    // falls on.
    static final String CONTAINING_GEOFENCE_IDS_SQL = """
        SELECT id FROM geofences
        WHERE organization_id = ? AND is_active
          AND ST_Covers(area, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
        """;

    private static final String PREVIOUSLY_INSIDE_GEOFENCE_IDS_SQL = """
        SELECT geofence_id FROM vehicle_fence_state
        WHERE vehicle_id = ? AND is_inside = true
        """;

    private final JdbcTemplate jdbcTemplate;

    public GeofenceEvaluator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Task 2.2/2.3: set-comparison between "currently inside" (task 2.1's
    // query) and "previously inside" (vehicle_fence_state), reduced through
    // FenceTransition.from() per geofence. Only ENTERED/EXITED survive into
    // the returned list.
    public List<GeofenceTransitionResult> evaluate(UUID vehicleId, UUID organizationId, double lat, double lon) {
        Set<UUID> currentlyInside = containingGeofenceIds(organizationId, lat, lon);
        Set<UUID> previouslyInside = previouslyInsideGeofenceIds(vehicleId);

        Set<UUID> relevantGeofenceIds = new HashSet<>(currentlyInside);
        relevantGeofenceIds.addAll(previouslyInside);

        List<GeofenceTransitionResult> results = new ArrayList<>();
        for (UUID geofenceId : relevantGeofenceIds) {
            boolean wasInside = previouslyInside.contains(geofenceId);
            boolean isInside = currentlyInside.contains(geofenceId);
            FenceTransition transition = FenceTransition.from(wasInside, isInside);
            if (transition != FenceTransition.NONE) {
                results.add(new GeofenceTransitionResult(geofenceId, transition));
            }
        }
        return results;
    }

    // Task 2.1: the single indexed spatial query, exposed package-private so
    // GeofenceEvaluatorTest can exercise it directly (test 6.5's overlap
    // proof does not need vehicle_fence_state at all).
    Set<UUID> containingGeofenceIds(UUID organizationId, double lat, double lon) {
        return new HashSet<>(jdbcTemplate.query(
            CONTAINING_GEOFENCE_IDS_SQL,
            ps -> {
                ps.setObject(1, organizationId);
                // ST_MakePoint(x, y): geographic x is longitude, y is latitude.
                ps.setDouble(2, lon);
                ps.setDouble(3, lat);
            },
            (rs, rowNum) -> (UUID) rs.getObject("id")
        ));
    }

    private Set<UUID> previouslyInsideGeofenceIds(UUID vehicleId) {
        return new HashSet<>(jdbcTemplate.query(
            PREVIOUSLY_INSIDE_GEOFENCE_IDS_SQL,
            ps -> ps.setObject(1, vehicleId),
            (rs, rowNum) -> (UUID) rs.getObject("geofence_id")
        ));
    }
}
