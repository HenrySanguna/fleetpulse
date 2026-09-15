-- Task 1.3 ("umbral de parada configurable por organizacion"): unlike
-- FleetpulseGeofencingProperties/FleetpulseMotionDetectionProperties (global
-- app-level tuning values design.md/proposal.md never asked to vary per
-- tenant), the stop threshold is explicitly required to be per-organization
-- by the spec itself. A DB column -- not a second Spring @ConfigurationProperties
-- mechanism -- is the only way for two different organizations running the
-- same `processor` deployment to genuinely have different values. 300
-- seconds (5 minutes) matches design.md's own stated default
-- ("por defecto 5 minutos").
ALTER TABLE organizations
    ADD COLUMN trip_stop_threshold_secs INTEGER NOT NULL DEFAULT 300;

ALTER TABLE organizations
    ADD CONSTRAINT chk_organizations_trip_stop_threshold_secs CHECK (trip_stop_threshold_secs > 0);

-- Tasks 1.1/1.4/1.5: one row per CLOSED trip (TripSegmenter/TripSegmentationTask,
-- processor module). "Closed" means a subsequent stop/idle run at least
-- trip_stop_threshold_secs long was actually observed -- see
-- TripSegmenter's own class comment for why the still-open trailing trip at
-- the end of a processing window is never represented by a row here.
--
-- organization_id is a direct copy of the owning vehicle's own
-- organization_id (denormalized, not joined through vehicles at query
-- time) -- the same "every org-scoped entity table carries its own
-- organization_id" convention geofences (V8) and geofence_alerts (V9)
-- already established, needed here so the activity-report query (task 4.3)
-- can filter/aggregate trips by organization without an extra join.
--
-- uq_trips_vehicle_started_at (task 1.5's idempotency key) is enforced as
-- an explicit named UNIQUE constraint, not the primary key: `id` stays a
-- synthetic UUID like every other entity table (geofences, geofence_alerts)
-- because the console's activity report (task 4.3) needs a stable
-- individual-row identifier for its trip list, which a composite
-- (vehicle_id, started_at) key would also serve but less conventionally
-- for this codebase.
CREATE TABLE trips (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    vehicle_id      UUID NOT NULL REFERENCES vehicles (id),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ NOT NULL,
    distance_km     REAL NOT NULL,
    duration_secs   INTEGER NOT NULL,
    idle_secs       INTEGER NOT NULL,
    max_speed_kmh   REAL NOT NULL,
    avg_speed_kmh   REAL NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_trips_vehicle_started_at UNIQUE (vehicle_id, started_at),
    CONSTRAINT chk_trips_ended_after_started CHECK (ended_at >= started_at)
);

-- The console's per-vehicle trip list and the activity report both read
-- "most recent trips first" for one vehicle or for a whole organization --
-- same newest-first pagination shape geofence_alerts' own indexes (V9)
-- already established for its console panel.
CREATE INDEX idx_trips_vehicle_started_at ON trips (vehicle_id, started_at DESC);
CREATE INDEX idx_trips_organization_started_at ON trips (organization_id, started_at DESC);
