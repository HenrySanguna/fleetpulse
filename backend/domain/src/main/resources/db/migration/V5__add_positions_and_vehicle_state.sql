-- positions is the append-only telemetry history, partitioned by time so
-- that old partitions can be dropped cheaply by retention instead of a bulk
-- DELETE. pg_partman creates the actual partitions and their retention
-- policy (change 03, section "pg_partman y mantenimiento programado"); this
-- migration only declares the partitioned parent, its primary key, and its
-- indexes -- with zero partitions, positions cannot accept inserts yet.
CREATE TABLE positions (
    vehicle_id  UUID NOT NULL REFERENCES vehicles (id),
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    location    GEOGRAPHY(Point, 4326) NOT NULL,
    speed_kmh   REAL,
    heading     REAL,
    ignition    BOOLEAN,
    -- The primary key doubles as the deduplication key: a resent point with
    -- the same vehicle_id/recorded_at collides here and is dropped with
    -- ON CONFLICT DO NOTHING by the batch writer (design.md), not by an
    -- in-memory check that would be lost on restart.
    PRIMARY KEY (vehicle_id, recorded_at)
) PARTITION BY RANGE (recorded_at);

-- BRIN on an append-only table whose physical order correlates with time
-- costs orders of magnitude less than a B-tree and serves range queries
-- just as well (design.md).
CREATE INDEX idx_positions_recorded_at ON positions USING BRIN (recorded_at);
CREATE INDEX idx_positions_location ON positions USING GIST (location);

-- vehicle_state holds only the latest known position/motion/online status
-- per vehicle, so the map never has to query the partitioned history table.
-- location/recorded_at/motion_state are nullable because a row can be
-- created by the presence consumer (change 03, "Presencia con LWT") before
-- the vehicle has ever reported a position; online is always known.
CREATE TABLE vehicle_state (
    vehicle_id   UUID PRIMARY KEY REFERENCES vehicles (id),
    location     GEOGRAPHY(Point, 4326),
    recorded_at  TIMESTAMPTZ,
    motion_state VARCHAR(32),
    online       BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT chk_vehicle_state_motion_state
        CHECK (motion_state IS NULL OR motion_state IN ('MOVING', 'IDLING', 'STOPPED'))
);
