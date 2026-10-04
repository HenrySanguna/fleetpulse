-- One-time backfill: earlier versions of the daily rollup summed only the
-- hours inside a sliding window, so a day's row ended up holding just its
-- trailing hours. vehicle_hourly is complete, so rebuild every day from it.
INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at)
SELECT vehicle_id, organization_id, (hour AT TIME ZONE 'UTC')::date AS day,
       SUM(distance_km), SUM(moving_secs), SUM(idle_secs), MAX(max_speed_kmh), now()
FROM vehicle_hourly
GROUP BY vehicle_id, organization_id, day
ON CONFLICT (vehicle_id, day) DO UPDATE SET
    distance_km = EXCLUDED.distance_km,
    moving_secs = EXCLUDED.moving_secs,
    idle_secs = EXCLUDED.idle_secs,
    max_speed_kmh = EXCLUDED.max_speed_kmh,
    updated_at = EXCLUDED.updated_at;
