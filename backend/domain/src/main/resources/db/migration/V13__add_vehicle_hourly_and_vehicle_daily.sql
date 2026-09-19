-- Tasks 4.1/4.2 (design.md "Rollups en lugar de continuous aggregates"):
-- design.md gives a literal CREATE TABLE sketch for vehicle_hourly with a
-- composite PRIMARY KEY (vehicle_id, hour) -- unlike trips/alerts (design.md
-- gave no schema for either), this is the one thing design.md is explicit
-- and literal about, so it is followed as written rather than adding a
-- synthetic `id` the way trips/alerts do: a rollup row has no per-row
-- console list-item use case (no "click into one hourly rollup" screen),
-- unlike a trip or an alert, so the composite key doubles as the natural
-- business key without losing anything.
--
-- organization_id is still denormalized directly onto both tables (not
-- joined through vehicles at query time), the same "every org-scoped table
-- carries its own organization_id" convention trips/vehicle_destinations/alerts
-- already established -- needed so the activity report (task 4.3/WU6) can
-- filter/aggregate by organization without an extra join.
--
-- Column names use the `_kmh` suffix (max_speed_kmh) rather than design.md's
-- bare `max_speed` -- a naming-consistency choice only, matching trips' own
-- max_speed_kmh/avg_speed_kmh columns; the semantics are exactly what
-- design.md's sketch describes. avg_speed is deliberately NOT carried here
-- (design.md's own sketch has no such column for vehicle_hourly): it is
-- fully derivable from distance_km/moving_secs when a consumer needs it,
-- same "don't store what recomputes trivially" reasoning implied by
-- design.md's own omission.
--
-- max_speed_kmh stays NULLable (design.md: "max_speed real" with no NOT
-- NULL) since an hour can in principle contain positions with no reported
-- speed at all.
CREATE TABLE vehicle_hourly (
    vehicle_id      UUID NOT NULL REFERENCES vehicles (id),
    organization_id UUID NOT NULL REFERENCES organizations (id),
    hour            TIMESTAMPTZ NOT NULL,
    distance_km     REAL NOT NULL,
    moving_secs     INTEGER NOT NULL,
    idle_secs       INTEGER NOT NULL,
    max_speed_kmh   REAL,
    updated_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vehicle_id, hour)
);

-- Activity report / console charts (task 4.3/4.4, WU6) read "an
-- organization's vehicles over a date range" and "one vehicle over a date
-- range" -- same newest-first-by-vehicle-or-org shape trips'/alerts' own
-- indexes already establish, just ascending here since a report range scan
-- naturally reads oldest-to-newest within the requested window.
CREATE INDEX idx_vehicle_hourly_organization_hour ON vehicle_hourly (organization_id, hour);

-- Task 4.2: vehicle_daily is a pure SQL derivation (SUM/MAX) of
-- vehicle_hourly, not a second Java segmentation algorithm -- see
-- VehicleRollupTask's own class comment for the full reasoning. `day` is a
-- plain DATE (UTC calendar day of `hour`, computed at write time): this MVP
-- has no per-organization timezone concept anywhere else either (matching
-- ETA's own documented "straight-line distance, no real routing" class of
-- simplification), so a single UTC day boundary is used everywhere rather
-- than inventing per-tenant timezone handling this change never asked for.
CREATE TABLE vehicle_daily (
    vehicle_id      UUID NOT NULL REFERENCES vehicles (id),
    organization_id UUID NOT NULL REFERENCES organizations (id),
    day             DATE NOT NULL,
    distance_km     REAL NOT NULL,
    moving_secs     INTEGER NOT NULL,
    idle_secs       INTEGER NOT NULL,
    max_speed_kmh   REAL,
    updated_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vehicle_id, day)
);

CREATE INDEX idx_vehicle_daily_organization_day ON vehicle_daily (organization_id, day);
