-- Task 2.1 ("asignacion de destino a un vehiculo"): one ACTIVE destination
-- per vehicle -- assigning a new destination REPLACES the previous row (an
-- upsert, never an append-only history: unlike trips (V10), design.md/
-- proposal.md never ask for a destination-assignment log). organization_id
-- is a direct copy of the owning vehicle's own organization_id
-- (denormalized, not joined through vehicles at query time), the same
-- "every org-scoped entity table carries its own organization_id"
-- convention trips (V10) and geofences/geofence_alerts (V8/V9) already
-- established -- needed so the live recalculation path (task 2.4,
-- processor module) and the MQTT publish can resolve the owning
-- organization for every eligible telemetry message without joining
-- through vehicles.
--
-- destination is GEOGRAPHY(Point, 4326), matching vehicle_state.location's
-- own type (V5) -- the same coordinate representation every other
-- lat/lon-bearing table in this schema already uses.
--
-- eta_seconds/eta_margin_seconds/eta_calculated_at are owned by a DIFFERENT
-- writer than destination/assigned_at: the live processor write path (task
-- 2.4, EtaRecalculationDispatcher/JdbcVehicleDestinationEtaWriter), not the
-- assignment endpoint (api module, VehicleDestinationController). This is
-- the same "two different writers, same row, different owned columns"
-- shape vehicle_state's own `online` column already established (owned by
-- the presence consumer, distinct from location/motion_state's telemetry-
-- writer ownership) -- not a novel pattern for this schema. All three ETA
-- columns are NULL until the first live position after a destination is
-- assigned computes them, and are reset back to NULL whenever a NEW
-- destination replaces the previous one (assignVehicleDestination, api
-- module): the old ETA describes a destination that no longer applies, so
-- leaving it in place would misrepresent a stale estimate as current,
-- exactly what the DoD's "never an exact time without margin" guards
-- against in spirit (a stale-but-precise-looking number is its own kind of
-- false precision).
CREATE TABLE vehicle_destinations (
    vehicle_id         UUID PRIMARY KEY REFERENCES vehicles (id),
    organization_id    UUID NOT NULL REFERENCES organizations (id),
    destination        GEOGRAPHY(Point, 4326) NOT NULL,
    assigned_at         TIMESTAMPTZ NOT NULL,
    eta_seconds         INTEGER,
    eta_margin_seconds  INTEGER,
    eta_calculated_at   TIMESTAMPTZ,
    CONSTRAINT chk_vehicle_destinations_eta_seconds CHECK (eta_seconds IS NULL OR eta_seconds >= 0),
    CONSTRAINT chk_vehicle_destinations_eta_margin_seconds CHECK (eta_margin_seconds IS NULL OR eta_margin_seconds >= 0)
);
