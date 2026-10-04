package dev.fleetpulse.processor.rollups;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;

// Task 4.2 ("tabla vehicle_daily derivada de la horaria"), resolved as a
// pure SQL aggregation over the just-recomputed vehicle_hourly rows, not a
// second Java segmentation/aggregation class: unlike vehicle_hourly (which
// genuinely needs a database-free algorithm to classify per-leg motion state
// and apportion distance/moving/idle time from raw positions -- exactly the
// same reason TripSegmenter/AlertSilenceEngine exist as pure classes),
// vehicle_daily is a literal SUM/MAX GROUP BY over rows vehicle_hourly
// already computed. Reimplementing that as a second Java aggregator would
// only re-derive, less reliably, what a single GROUP BY already guarantees
// -- this work unit's own documented resolution of the open design question
// ("does vehicle_daily recompute via its own scheduled task, or
// synchronously whenever an hourly row changes?"): neither, literally --
// VehicleRollupTask runs this recompute as the LAST step of the SAME
// scheduled run that just wrote vehicle_hourly (not a fully independent
// @Scheduled cadence, which would risk reading hourly rows the current run
// has not finished writing yet, and not per-row-write synchronous
// recomputation, which would re-run this GROUP BY once per hourly row
// instead of once per batch).
//
// ON CONFLICT (vehicle_id, day) DO UPDATE keeps the recompute idempotent:
// re-running it over the same hours yields the same totals, and a
// still-in-progress "today" converges to the complete day's totals as more of
// its hours close. `day` uses `hour AT TIME ZONE 'UTC'` (see V13's own
// migration comment for why UTC, not a per-organization timezone).
//
// The SUM range starts at the UTC day start of windowStart, NOT at
// windowStart itself: the window slides every run, so the first day it touches
// is usually only partially inside it, and summing just the in-window hours
// would overwrite that day's total with a partial one. vehicle_hourly keeps
// the older hours of that day, so reading from the day start makes every
// touched day a complete recompute. GROUP BY only emits a day for a
// (vehicle_id, day) pair that has at least one vehicle_hourly row in the
// range -- a vehicle with no activity gets no spurious zero row.
@Component
public class JdbcDailyRollupWriter {

    private static final String UPSERT_SQL = """
        INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at)
        SELECT vehicle_id, organization_id, (hour AT TIME ZONE 'UTC')::date AS day,
               SUM(distance_km), SUM(moving_secs), SUM(idle_secs), MAX(max_speed_kmh), ?
        FROM vehicle_hourly
        WHERE hour >= ? AND hour < ?
        GROUP BY vehicle_id, organization_id, day
        ON CONFLICT (vehicle_id, day) DO UPDATE SET
            distance_km = EXCLUDED.distance_km,
            moving_secs = EXCLUDED.moving_secs,
            idle_secs = EXCLUDED.idle_secs,
            max_speed_kmh = EXCLUDED.max_speed_kmh,
            updated_at = EXCLUDED.updated_at
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDailyRollupWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recompute(Instant windowStart, Instant windowEnd) {
        Instant firstDayStart = windowStart.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(UPSERT_SQL, Timestamp.from(Instant.now()), Timestamp.from(firstDayStart), Timestamp.from(windowEnd));
    }
}
