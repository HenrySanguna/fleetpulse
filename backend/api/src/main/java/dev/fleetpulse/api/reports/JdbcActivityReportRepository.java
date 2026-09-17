package dev.fleetpulse.api.reports;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Task 4.3/4.4 (spec.md "Requirement: Informes servidos desde agregados" --
// the plan must never touch `positions`): reads only `vehicle_daily` and
// `trips`, plain JdbcTemplate, no JPA entity -- same "no ORM for a table
// another module already owns with raw SQL" convention JdbcAlertsRepository
// (task 3.4) already follows for this module's read-only report queries.
//
// Design decision, documented per this project's "note deviations, don't
// silently freelance" convention: this repository deliberately reads
// vehicle_daily only, never vehicle_hourly. vehicle_daily already carries
// one row per (vehicle, day) with distance_km/moving_secs/idle_secs/
// max_speed_kmh -- exactly what both the range-wide summary (a SUM/MAX over
// those rows) and the "Distancia por día" chart (one bar per row) need.
// Reading vehicle_hourly in addition would only add rows to re-aggregate
// into the same daily buckets vehicle_daily already materializes -- neither
// design.md nor the console's own pre-existing chart placeholder asks for
// an hourly-granularity view anywhere in this report, so there is nothing
// for the finer table to contribute here. Test 5.7 (ActivityReportQueryPlanTest,
// domain module) proves neither query below ever scans `positions`.
//
// Performance note (DoD "un informe de una semana... en tiempo aceptable
// sin tocar positions"): a one-week range reads at most 7 vehicle_daily rows
// and a handful of trips rows per vehicle -- both tables are already
// pre-aggregated/low-cardinality by construction (vehicle_daily has one row
// per vehicle per calendar day, full stop), so this is fast by construction,
// not something a load test is needed to demonstrate.
@Component
class JdbcActivityReportRepository {

    private static final String SELECT_DAILY_SQL = """
        SELECT day, distance_km, moving_secs, idle_secs, max_speed_kmh
        FROM vehicle_daily
        WHERE organization_id = ? AND vehicle_id = ? AND day BETWEEN ? AND ?
        ORDER BY day ASC
        """;

    // Newest-first, matching idx_trips_vehicle_started_at's own DESC ordering
    // (V10) -- an index-friendly ORDER BY, not just a display preference.
    private static final String SELECT_TRIPS_SQL = """
        SELECT id, started_at, ended_at, distance_km, duration_secs, idle_secs, max_speed_kmh
        FROM trips
        WHERE organization_id = ? AND vehicle_id = ? AND started_at >= ? AND started_at <= ?
        ORDER BY started_at DESC
        """;

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    JdbcActivityReportRepository(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<DailyRollupRow> findDaily(UUID organizationId, UUID vehicleId, LocalDate from, LocalDate to) {
        return jdbcTemplate.getObject().query(
            SELECT_DAILY_SQL,
            (rs, rowNum) -> toDailyRow(rs),
            organizationId, vehicleId, Date.valueOf(from), Date.valueOf(to)
        );
    }

    List<ActivityTripResponse> findTrips(UUID organizationId, UUID vehicleId, Instant from, Instant to) {
        return jdbcTemplate.getObject().query(
            SELECT_TRIPS_SQL,
            (rs, rowNum) -> toTripResponse(rs),
            organizationId, vehicleId, Timestamp.from(from), Timestamp.from(to)
        );
    }

    private static DailyRollupRow toDailyRow(ResultSet rs) throws SQLException {
        Float maxSpeedKmh = rs.getFloat("max_speed_kmh");
        if (rs.wasNull()) {
            maxSpeedKmh = null;
        }
        return new DailyRollupRow(
            rs.getDate("day").toLocalDate(),
            rs.getFloat("distance_km"),
            rs.getInt("moving_secs"),
            rs.getInt("idle_secs"),
            maxSpeedKmh
        );
    }

    private static ActivityTripResponse toTripResponse(ResultSet rs) throws SQLException {
        return new ActivityTripResponse(
            (UUID) rs.getObject("id"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("ended_at").toInstant(),
            rs.getFloat("distance_km"),
            rs.getInt("duration_secs") / 60,
            rs.getInt("idle_secs") / 60,
            rs.getFloat("max_speed_kmh")
        );
    }

    // Repository-internal row, never serialized directly: ActivityReportService
    // needs moving_secs/idle_secs to compute the summary's totals, but
    // DailyDistancePointResponse (the chart's own response shape) only
    // carries day/distanceKm -- one extra type here is simpler than forcing
    // the response DTO to carry fields the chart never renders.
    record DailyRollupRow(LocalDate day, double distanceKm, int movingSecs, int idleSecs, Float maxSpeedKmh) {
    }
}
