package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceMembershipState;
import dev.fleetpulse.geocore.FenceTransition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
// This class stays read-only against vehicle_fence_state/geofences: WU4
// extended it with three more read methods (loadActiveMembershipStates,
// bufferedContainmentAmong, loadRules) that GeofenceRuleDispatcher composes
// with geo-core's FenceMembershipDetector (WU3) and GeofenceRuleEngine
// (WU4), but it still neither writes is_inside/since/pending_since nor
// decides which alerts fire -- that split is unchanged from WU2's original
// design, only the number of reads grew. Persisting the confirmed
// transition is JdbcVehicleFenceStateWriter's job (WU4), wired into
// JdbcTelemetryPositionWriter's existing guarded write path per the
// task-2.4 architecture decision (tasks.md forecast). A geofence that was
// previously inside but has since been deactivated (is_active = false) is
// excluded from the containment query and therefore still reports EXITED
// here: the vehicle is no longer inside any geofence a caller should track,
// deactivated or not, and leaving a stale is_inside = true row behind would
// silently hide that.
//
// evaluate() below (tasks 2.1-2.3) is intentionally left as WU2 built it --
// an undamped, single-shot ENTERED/EXITED/NONE comparison with no
// hysteresis -- and stays unused by production wiring for exactly that
// reason: publishing on undamped transitions is the boundary-oscillation
// failure WU3/WU4 exist to prevent. It remains here, still covered by its
// own WU2 tests, as the direct proof of tasks 2.1-2.3's "single query, set
// comparison, FenceTransition" contract in isolation from damping and rule
// dispatch.
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

    // WU4: broader than PREVIOUSLY_INSIDE_GEOFENCE_IDS_SQL above -- also
    // picks up a row with a PENDING (not yet confirmed) transition even
    // while is_inside is still false, e.g. a pending entry. Without this, a
    // reading that reverts back to the confirmed state before ever showing
    // up in the narrower "is_inside = true" set would leave its
    // pending_since stuck forever instead of being discarded (design.md,
    // "se descarta sin emitir nada", task 3.2).
    private static final String ACTIVE_MEMBERSHIP_STATES_SQL = """
        SELECT geofence_id, is_inside, since, pending_since, pending_reading_count, dwell_alerted
        FROM vehicle_fence_state
        WHERE vehicle_id = ? AND (is_inside = true OR pending_since IS NOT NULL)
        """;

    // Task 3.3's asymmetric exit margin. Deliberately NOT folded into
    // CONTAINING_GEOFENCE_IDS_SQL's org-wide GiST-indexed scan above:
    // ST_Buffer(area, N) is a per-row computed geography the GiST index on
    // `area` cannot answer (the same reason task 2.1's own comment documents
    // for ST_Contains), so this stays a second, separately-scoped query --
    // bounded by id = ANY(?), the handful of geofences a single vehicle is
    // already tracked against, not the whole organization's geofence table.
    // That is exactly why it does not need (and would not benefit from) the
    // same index-usage guarantee test 6.9 proves for the org-wide query.
    private static final String BUFFERED_CONTAINMENT_AMONG_SQL = """
        SELECT id FROM geofences
        WHERE id = ANY (?)
          AND ST_Covers(ST_Buffer(area, ?), ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
        """;

    // Task 4.1: rule/dwell_secs metadata, fetched separately from the
    // containment queries above since it is not a spatial predicate, and a
    // geofence can be relevant to GeofenceRuleDispatcher (previously inside,
    // or newly entered) without vehicle_fence_state carrying anything more
    // than its id.
    private static final String LOAD_RULES_SQL = """
        SELECT id, rule, dwell_secs FROM geofences
        WHERE id = ANY (?)
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

    // WU4 (GeofenceRuleDispatcher): every geofence this vehicle is either
    // confirmed inside or has a pending transition in progress for, read
    // back into geo-core's own FenceMembershipState shape. See
    // ACTIVE_MEMBERSHIP_STATES_SQL's comment for why this is broader than
    // previouslyInsideGeofenceIds().
    public Map<UUID, GeofenceMembershipRecord> loadActiveMembershipStates(UUID vehicleId) {
        return jdbcTemplate.query(
            ACTIVE_MEMBERSHIP_STATES_SQL,
            ps -> ps.setObject(1, vehicleId),
            rs -> {
                Map<UUID, GeofenceMembershipRecord> states = new HashMap<>();
                while (rs.next()) {
                    UUID geofenceId = (UUID) rs.getObject("geofence_id");
                    FenceMembershipState state = new FenceMembershipState(
                        rs.getBoolean("is_inside"),
                        rs.getTimestamp("since").toInstant(),
                        toInstant(rs.getTimestamp("pending_since")),
                        rs.getInt("pending_reading_count")
                    );
                    states.put(geofenceId, new GeofenceMembershipRecord(state, rs.getBoolean("dwell_alerted")));
                }
                return states;
            }
        );
    }

    // WU4: which of the given geofence ids currently have the point inside
    // their BUFFERED boundary (ST_Buffer(area, bufferMeters)). Callers only
    // need to ask this for geofences NOT already in containingGeofenceIds()'s
    // result: strict containment always implies buffered containment
    // (FenceMembershipSample's own compact constructor enforces this
    // invariant), so re-querying those would be redundant.
    public Set<UUID> bufferedContainmentAmong(Set<UUID> geofenceIds, double lat, double lon, double bufferMeters) {
        if (geofenceIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcTemplate.query(
            BUFFERED_CONTAINMENT_AMONG_SQL,
            ps -> {
                Array idArray = ps.getConnection().createArrayOf("uuid", geofenceIds.toArray());
                ps.setArray(1, idArray);
                ps.setDouble(2, bufferMeters);
                // ST_MakePoint(x, y): geographic x is longitude, y is latitude.
                ps.setDouble(3, lon);
                ps.setDouble(4, lat);
            },
            (rs, rowNum) -> (UUID) rs.getObject("id")
        ));
    }

    // WU4: rule + dwell_secs for whichever geofences turned out relevant to
    // a message, keyed by geofence id. A geofence id present in the input
    // set but absent from the result means it was hard-deleted since being
    // referenced by vehicle_fence_state or the containment query -- callers
    // must treat that as "nothing left to evaluate this geofence against".
    public Map<UUID, GeofenceRule> loadRules(Set<UUID> geofenceIds) {
        if (geofenceIds.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query(
            LOAD_RULES_SQL,
            ps -> {
                Array idArray = ps.getConnection().createArrayOf("uuid", geofenceIds.toArray());
                ps.setArray(1, idArray);
            },
            rs -> {
                Map<UUID, GeofenceRule> rules = new HashMap<>();
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    GeofenceRuleType type = GeofenceRuleType.fromDbValue(rs.getString("rule"));
                    Integer dwellSecs = (Integer) rs.getObject("dwell_secs");
                    rules.put(id, new GeofenceRule(type, dwellSecs));
                }
                return rules;
            }
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
