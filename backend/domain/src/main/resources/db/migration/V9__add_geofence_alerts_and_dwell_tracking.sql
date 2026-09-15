-- WU4 (tasks 2.4/4.1-4.3): resolves the two schema gaps WU3's own progress
-- notes flagged, now that the wiring/rule-dispatch pipeline that actually
-- needs them exists.
--
-- pending_reading_count persists geo-core's FenceMembershipState.pendingReadingCount
-- (WU3) across a `processor` restart, the same way pending_since already
-- persists the pending transition's start time (V8). Resolved by adding the
-- column, not by going duration-only: task 3.1's damping already implements
-- the N-consecutive-readings confirmation criterion in geo-core, and leaving
-- it unpersisted here would silently make that code path dead in
-- production (it would always lose to duration-only confirmation after
-- every restart).
--
-- dwell_alerted tracks whether the on_dwell rule has already fired for the
-- CURRENT confirmed-inside stay (`since`), so a vehicle that keeps sending
-- telemetry while parked inside a dwell geofence gets exactly one alert, not
-- one per message past the threshold (spec: "no se emiten alertas
-- adicionales mientras el vehiculo siga dentro sin salir"). Owned entirely
-- by the write path (GeofenceRuleDispatcher/GeofenceRuleEngine): reset to
-- false whenever a confirmed transition changes `since`/`is_inside`, not by
-- a trigger -- consistent with every other bookkeeping column on this table.
ALTER TABLE vehicle_fence_state
    ADD COLUMN pending_reading_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN dwell_alerted BOOLEAN NOT NULL DEFAULT false;

-- Task 4.3: history for the console's alerts panel. No geometry column here
-- -- geofence_id is enough to join back to `geofences` for the polygon/name
-- when a panel needs it, so this stays a plain event log, not a second
-- spatial table.
CREATE TABLE geofence_alerts (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    vehicle_id      UUID NOT NULL REFERENCES vehicles (id),
    geofence_id     UUID NOT NULL REFERENCES geofences (id),
    alert_type      VARCHAR(16) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_geofence_alerts_alert_type CHECK (alert_type IN ('enter', 'exit', 'dwell'))
);

-- The console panel's primary read pattern is "recent alerts for my
-- organization" (design.md's dispatcher-facing alerts feed, published on
-- fleet/{orgId}/alerts); occurred_at DESC matches how a panel paginates
-- newest-first. A per-vehicle variant supports a vehicle detail view the
-- same way.
CREATE INDEX idx_geofence_alerts_organization_occurred ON geofence_alerts (organization_id, occurred_at DESC);
CREATE INDEX idx_geofence_alerts_vehicle_occurred ON geofence_alerts (vehicle_id, occurred_at DESC);
