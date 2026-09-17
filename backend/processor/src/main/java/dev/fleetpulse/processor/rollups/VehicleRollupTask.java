package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.processor.config.FleetpulseRollupsProperties;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import dev.fleetpulse.processor.trips.PositionSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Tasks 4.1/4.2: runs on its own schedule, entirely over already-persisted
// `positions`/`vehicle_hourly` -- never on the live MQTT ingestion path,
// same "not on the write-fast path" reasoning design.md gives trip
// segmentation ("no en el camino de ingesta"), which applies here just as
// much: a rollup used by reports/charts (WU6) has no reason to add latency
// to the guarded live write path.
//
// Every 15 minutes is this change's own choice (neither proposal.md nor
// design.md states an exact frequency): coarser than
// TripSegmentationTask's own PT5M, deliberately -- rollups feed reports and
// dashboards (WU6), not a live console panel, so near-real-time freshness is
// not required, and unlike trip segmentation's narrow incremental
// watermark-based read, every run here re-reads a full
// FleetpulseRollupsProperties.window() span (default 24h) of raw positions
// per vehicle, so a slightly longer interval keeps that heavier per-run cost
// from repeating too often.
//
// Reuses the SAME VehicleMotionStreakTracker bean (and therefore the SAME
// MotionConfig thresholds/debounce) the live write path and trip
// segmentation already use, replayed per vehicle from an empty "known
// states" map every run -- HourlyRollupAggregator's own class comment
// explains why re-classifying the same window fully, from scratch, every
// run is correct here: the whole run is idempotent bulk recomputation, not
// incremental state carried across runs.
@Component
public class VehicleRollupTask {

    private static final Logger log = LoggerFactory.getLogger(VehicleRollupTask.class);

    private final JdbcRollupReader rollupReader;
    private final JdbcHourlyRollupWriter hourlyRollupWriter;
    private final JdbcDailyRollupWriter dailyRollupWriter;
    private final VehicleMotionStreakTracker motionStreakTracker;
    private final FleetpulseRollupsProperties rollupsProperties;

    public VehicleRollupTask(
        JdbcRollupReader rollupReader,
        JdbcHourlyRollupWriter hourlyRollupWriter,
        JdbcDailyRollupWriter dailyRollupWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseRollupsProperties rollupsProperties
    ) {
        this.rollupReader = rollupReader;
        this.hourlyRollupWriter = hourlyRollupWriter;
        this.dailyRollupWriter = dailyRollupWriter;
        this.motionStreakTracker = motionStreakTracker;
        this.rollupsProperties = rollupsProperties;
    }

    @Scheduled(fixedDelayString = "PT15M")
    public void recalculateAllVehicles() {
        // windowEnd is the start of the CURRENT, still-open hour -- an
        // exclusive upper bound, so the still-in-progress hour is never
        // written as if it were a closed one (design.md: "recalcula las
        // horas cerradas recientes").
        Instant windowEnd = HourlyRollupAggregator.truncateToHour(Instant.now());
        Instant windowStart = HourlyRollupAggregator.truncateToHour(windowEnd.minus(rollupsProperties.window()));

        List<JdbcRollupReader.VehicleRef> vehicles = rollupReader.loadVehicles();
        int recomputedHours = 0;
        for (JdbcRollupReader.VehicleRef vehicle : vehicles) {
            recomputedHours += recalculateVehicleHourly(vehicle, windowStart, windowEnd);
        }

        // Task 4.2: derives vehicle_daily from the vehicle_hourly rows this
        // same run just wrote, as the run's last step -- see
        // JdbcDailyRollupWriter's own class comment for why this is neither
        // a separate @Scheduled task nor a synchronous per-row recompute.
        dailyRollupWriter.recompute(windowStart, windowEnd);

        log.info(
            "Rollup recompute run refreshed {} hourly row(s) across {} vehicle(s), window [{}, {})",
            recomputedHours, vehicles.size(), windowStart, windowEnd
        );
    }

    private int recalculateVehicleHourly(JdbcRollupReader.VehicleRef vehicle, Instant windowStart, Instant windowEnd) {
        List<PositionSample> positions = rollupReader.positionsForRecompute(vehicle.vehicleId(), windowStart, windowEnd);
        if (positions.isEmpty()) {
            return 0;
        }

        List<TelemetryMessage> asMessages = positions.stream()
            .map(p -> new TelemetryMessage(vehicle.vehicleId(), p.recordedAt(), p.lat(), p.lon(), p.speedKmh(), null, p.ignition()))
            .toList();
        List<VehicleMotionUpdate> motionUpdates = motionStreakTracker.computeUpdates(Map.of(), asMessages);

        List<HourlyRollupCandidate> rows = HourlyRollupAggregator.aggregate(
            vehicle.vehicleId(), vehicle.organizationId(), positions, motionUpdates, windowStart
        );
        hourlyRollupWriter.writeBatch(rows);
        return rows.size();
    }
}
