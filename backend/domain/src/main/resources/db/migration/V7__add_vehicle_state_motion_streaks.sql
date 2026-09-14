-- Task 4.2 (change 03): `MotionDetector.next(prev, sample, cfg)` (geo-core) is
-- a pure function that also needs, per sample, how long the vehicle has been
-- continuously below/above the stop/start speed thresholds
-- (`MotionSample.lowSpeedStreak`/`highSpeedStreak`). Those streak durations
-- are derived, not stored directly: what must survive between MQTT messages
-- is the timestamp each currently-active streak began, so the next message
-- can compute `recordedAt - streakStartedAt`. `vehicle_state.motion_state`
-- (V5) cannot hold that on its own -- it is constrained by
-- chk_vehicle_state_motion_state to only ever be NULL or one of
-- MotionState's three enum names, so it stays exactly what it already is:
-- the last computed MotionState, nothing more. These two columns are
-- internal bookkeeping for the guarded upsert in
-- JdbcTelemetryPositionWriter, not part of the public vehicle_state read
-- contract the map/API consume.
--
-- Both are nullable: NULL means "no streak currently active" (the vehicle's
-- last known speed sample was in the dead zone between the stop and start
-- thresholds, or no telemetry has been evaluated for this vehicle yet), the
-- same nullability rationale V5 already used for location/recorded_at/motion_state.
ALTER TABLE vehicle_state
    ADD COLUMN low_speed_streak_started_at TIMESTAMPTZ,
    ADD COLUMN high_speed_streak_started_at TIMESTAMPTZ;
