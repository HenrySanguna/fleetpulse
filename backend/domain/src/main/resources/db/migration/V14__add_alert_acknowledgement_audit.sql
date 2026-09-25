-- Task 3.4 addendum (T9, prod QA): audit WHO acknowledged an alert and WHEN
-- -- V12 only added a bare `acknowledged` boolean, with no such audit trail.
-- acknowledged_by is a UUID FK to `users` (the same dispatcher identity
-- CurrentDispatcher/AuthenticatedDispatcher already resolve from the request,
-- V2), not a denormalized email snapshot, matching alerts.vehicle_id's own
-- "reference the real row, not a copy of one column" convention; ON DELETE
-- SET NULL keeps a deleted dispatcher's past acknowledgements on the record
-- instead of blocking their deletion or cascading it into alert history.
-- Both columns stay NULL for every already-acknowledged row this migration
-- finds -- there is no way to retroactively know who did it or when, so a
-- fabricated backfill value would be worse than an honest NULL.
ALTER TABLE alerts
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_by UUID REFERENCES users (id) ON DELETE SET NULL;
