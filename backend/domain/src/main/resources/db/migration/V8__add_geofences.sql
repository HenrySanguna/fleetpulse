-- Geofences are a state machine, not a spatial query (proposal.md): knowing
-- whether a point is inside a polygon is trivial with ST_Contains, but
-- reliably detecting the *instant* of entry/exit -- without duplicate
-- alerts from GPS drift on the boundary -- needs the vehicle's previous
-- membership persisted, surviving a `processor` restart (design.md,
-- "Persistencia del estado de pertenencia").
--
-- `area` is always a Polygon, even for a circular geofence: design.md is
-- explicit that a circle is stored as the buffered polygon of its center
-- (`ST_Buffer`), not as a separate center/radius representation, so
-- evaluation always has exactly one code path (ST_Contains against a
-- polygon) instead of branching on geofence shape. The buffer itself is
-- computed by whichever write path creates the row (the geofence CRUD API,
-- not yet built) -- this migration only declares the column shape that
-- convention commits to.
--
-- Deviates from design.md's SQL sketch in naming only, not shape: `org_id`
-- -> `organization_id` (matches `vehicles.organization_id`, V3) and adds
-- `created_at` (every other org-scoped entity table already has one --
-- `vehicles`, `devices` -- design.md's sketch just omitted it).
CREATE TABLE geofences (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    name            VARCHAR(255) NOT NULL,
    area            GEOGRAPHY(Polygon, 4326) NOT NULL,
    rule            VARCHAR(16) NOT NULL,
    dwell_secs      INTEGER,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_geofences_rule CHECK (rule IN ('on_enter', 'on_exit', 'on_dwell')),
    -- dwell_secs is only meaningful for the on_dwell rule (design.md);
    -- mirrors mqtt_credentials' chk_mqtt_credentials_single_owner pattern
    -- (V3) of enforcing a field's conditional relevance in the schema
    -- itself, not only in application code.
    CONSTRAINT chk_geofences_dwell_secs CHECK (
        (rule = 'on_dwell' AND dwell_secs IS NOT NULL AND dwell_secs > 0) OR
        (rule <> 'on_dwell' AND dwell_secs IS NULL)
    )
);

CREATE INDEX idx_geofences_organization_id ON geofences (organization_id);

-- GiST on the geography column: this is what makes the evaluation query
-- (task 2.1, design.md "Evaluacion: una consulta, no N") a single indexed
-- spatial lookup across every active geofence at once, instead of an O(N)
-- loop evaluating one geofence at a time (test 6.9 proves no seq scan).
CREATE INDEX idx_geofences_area ON geofences USING GIST (area);

-- Tracks whether a vehicle is currently inside each geofence it has ever
-- been evaluated against, so a `processor` restart (or a late/reordered
-- telemetry message) can tell "already inside" apart from "just entered"
-- without replaying history. `since` is when the CONFIRMED state began;
-- `pending_since` is when a not-yet-confirmed transition candidate was
-- first observed (design.md, "Confirmacion temporal") -- NULL when there is
-- no transition currently pending. A row only exists once a vehicle has
-- been evaluated against that geofence at least once; there is
-- deliberately no default/pre-seeded row for every vehicle x geofence pair.
CREATE TABLE vehicle_fence_state (
    vehicle_id    UUID NOT NULL REFERENCES vehicles (id),
    geofence_id   UUID NOT NULL REFERENCES geofences (id),
    is_inside     BOOLEAN NOT NULL,
    since         TIMESTAMPTZ NOT NULL,
    pending_since TIMESTAMPTZ,
    PRIMARY KEY (vehicle_id, geofence_id)
);
