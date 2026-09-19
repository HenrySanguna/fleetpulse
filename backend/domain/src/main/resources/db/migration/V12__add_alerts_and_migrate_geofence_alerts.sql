-- Task 3.1 (06-add-trips-eta-alerts, WU3): resolves the alerts-table design
-- gap tasks.md's own Review Workload Forecast flagged before any WU3 code
-- was written. design.md ("Panel de alertas"): "todas las alertas (geocerca,
-- velocidad, ralenti, offline) comparten tabla" -- but 05-add-geofencing
-- already shipped geofence_alerts (V9) as its own single-purpose table, with
-- its own unprefixed enter/exit/dwell alert_type values, while the console's
-- already-built Alert model expects a DIFFERENT union entirely
-- (geofence_enter | geofence_exit | speeding | excessive_idle | offline).
--
-- Per the user's own explicit decision (a product decision, not an
-- implementation detail this work unit inferred): `alerts` is a genuinely
-- NEW, unified table covering every alert type this project will ever emit
-- (geofence_enter/geofence_exit/geofence_dwell now; speeding/excessive_idle
-- added by this same migration; offline reserved for a later change, not yet
-- built) -- geofence_alerts' existing rows are migrated into it (its history
-- is real production data from the already-shipped geofencing feature, never
-- discarded), every geofence_alerts consumer is retargeted at `alerts`
-- (processor's JdbcGeofenceAlertWriter -- there was no api-module
-- geofence-alerts read endpoint to retarget: confirmed by reading
-- backend/api, AlertsService is still 100% mock data, task 3.4/WU4's own job
-- to wire), and geofence_alerts itself is dropped at the end of this same
-- migration. One table, one place of truth going forward, not two tables for
-- the same concept.
--
-- context is the alert's sub-resource, when it has one: a real geofence id
-- for the three geofence_* types (kept as an actual FOREIGN KEY, preserving
-- geofence_alerts.geofence_id's own referential-integrity guarantee exactly
-- -- a FOREIGN KEY on a nullable column in PostgreSQL only constrains its
-- non-NULL values, so this applies cleanly to geofence-type rows while
-- staying NULL, unconstrained, for every other type), NULL for the
-- vehicle-level types this migration adds (speeding, excessive_idle) and for
-- offline. A genuinely non-geofence context type in a future change would
-- need to loosen or replace this FK -- documented here rather than silently
-- assumed away.
--
-- acknowledged is added now, ahead of task 3.4's own "marcado como atendida"
-- endpoint (WU4, explicitly out of this work unit's scope): design.md's own
-- "Panel de alertas" already states the console "permite marcarlas como
-- atendidas" as part of this SAME table's purpose, and adding the column now
-- avoids a second migration touching this table again for one boolean. It is
-- inert until WU4 builds the endpoint that sets it.
CREATE TABLE alerts (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    vehicle_id      UUID NOT NULL REFERENCES vehicles (id),
    alert_type      VARCHAR(24) NOT NULL,
    context         UUID REFERENCES geofences (id),
    occurred_at     TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    acknowledged    BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT chk_alerts_alert_type CHECK (
        alert_type IN ('geofence_enter', 'geofence_exit', 'geofence_dwell', 'speeding', 'excessive_idle', 'offline')
    )
);

-- Same "recent alerts for my organization" / "recent alerts for one vehicle"
-- read shapes geofence_alerts' own indexes already served (V9's comment).
CREATE INDEX idx_alerts_organization_occurred ON alerts (organization_id, occurred_at DESC);
CREATE INDEX idx_alerts_vehicle_occurred ON alerts (vehicle_id, occurred_at DESC);

-- Data migration: every existing geofence_alerts row (05-add-geofencing's
-- already-shipped history) carries forward -- only the alert_type's wire
-- value gains its "geofence_" prefix (JdbcGeofenceAlertWriter's own
-- retargeted INSERT_SQL writes this same prefixed form going forward, so
-- historical and new rows share one consistent vocabulary).
INSERT INTO alerts (id, organization_id, vehicle_id, alert_type, context, occurred_at, created_at)
SELECT id, organization_id, vehicle_id, 'geofence_' || alert_type, geofence_id, occurred_at, created_at
FROM geofence_alerts;

-- Dropped in this same migration, not a follow-up: its data is already fully
-- copied above, and this project's own convention (JdbcGeofenceRepository's
-- soft-delete comment, V8) is to never leave two tables live for the same
-- concept once a migration exists to retire one of them.
DROP TABLE geofence_alerts;

-- Task 3.3 ("ventana de silencio por vehiculo, tipo y contexto"): internal
-- bookkeeping for AlertSilenceEngine's decision, the same role
-- vehicle_fence_state (V8) plays for geofence membership -- not part of the
-- public alerts history above. context is NOT NULL (a '' sentinel for the
-- vehicle-level alert types this migration's new rules use) rather than
-- nullable, because a composite PRIMARY KEY cannot contain a NULL column in
-- PostgreSQL; alerts.context above has no such constraint (its own primary
-- key is the synthetic `id`), so it stays a real nullable UUID instead.
CREATE TABLE alert_silence_state (
    vehicle_id    UUID NOT NULL REFERENCES vehicles (id),
    alert_type    VARCHAR(24) NOT NULL,
    context       VARCHAR(64) NOT NULL DEFAULT '',
    is_active     BOOLEAN NOT NULL,
    last_alert_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vehicle_id, alert_type, context)
);
