package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.geocore.PositionSample;
import dev.fleetpulse.processor.config.FleetpulseRollupsProperties;
import dev.fleetpulse.processor.telemetry.TelemetryActivity;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.VehicleMotionStreakTracker;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
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
    private final TelemetryActivity telemetryActivity;
    private final Clock clock;

    // Idle-run gate state, only touched by the scheduler thread. See
    // recalculateWhenNeeded().
    private boolean hasRun = false;
    private long lastRunActivityVersion = 0;
    private Instant lastRunWindowEnd = Instant.EPOCH;

    public VehicleRollupTask(
        JdbcRollupReader rollupReader,
        JdbcHourlyRollupWriter hourlyRollupWriter,
        JdbcDailyRollupWriter dailyRollupWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseRollupsProperties rollupsProperties
    ) {
        this(
            rollupReader, hourlyRollupWriter, dailyRollupWriter, motionStreakTracker, rollupsProperties,
            new TelemetryActivity(), Clock.systemUTC()
        );
    }

    @Autowired
    public VehicleRollupTask(
        JdbcRollupReader rollupReader,
        JdbcHourlyRollupWriter hourlyRollupWriter,
        JdbcDailyRollupWriter dailyRollupWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseRollupsProperties rollupsProperties,
        TelemetryActivity telemetryActivity
    ) {
        this(
            rollupReader, hourlyRollupWriter, dailyRollupWriter, motionStreakTracker, rollupsProperties,
            telemetryActivity, Clock.systemUTC()
        );
    }

    public VehicleRollupTask(
        JdbcRollupReader rollupReader,
        JdbcHourlyRollupWriter hourlyRollupWriter,
        JdbcDailyRollupWriter dailyRollupWriter,
        VehicleMotionStreakTracker motionStreakTracker,
        FleetpulseRollupsProperties rollupsProperties,
        TelemetryActivity telemetryActivity,
        Clock clock
    ) {
        this.rollupReader = rollupReader;
        this.hourlyRollupWriter = hourlyRollupWriter;
        this.dailyRollupWriter = dailyRollupWriter;
        this.motionStreakTracker = motionStreakTracker;
        this.rollupsProperties = rollupsProperties;
        this.telemetryActivity = telemetryActivity;
        this.clock = clock;
    }

    // The database is Neon, which only scales compute to zero after ~5
    // minutes without activity, so a run is skipped only when it provably
    // cannot change anything. A run is a full recomputation from the raw
    // positions of [windowStart, windowEnd), so it is a no-op when either:
    //  - its window is the previous run's window and this process persisted
    //    no positions since (same input, same output); or
    //  - no position can be recorded at or after windowStart (the window has
    //    moved past the newest known position), so it has nothing to write
    //    -- vehicle_hourly rows only exist for hours that hold positions,
    //    and vehicle_daily is derived from them. This is what ends the runs
    //    once the last telemetry is older than the window.
    // The window advances every hour, so a run is always needed after each
    // hour boundary while the window still holds telemetry: the daily
    // rollup is recomputed over the moving window, not only the hourly one.
    // The first run after startup always executes (this state is in memory).
    @Scheduled(fixedDelayString = "PT15M")
    public void recalculateWhenNeeded() {
        if (!hasRun) {
            telemetryActivity.seedFromDatabase(rollupReader.latestPositionRecordedAt());
        }
        TelemetryActivity.Snapshot activity = telemetryActivity.snapshot();
        Instant windowEnd = HourlyRollupAggregator.truncateToHour(clock.instant());
        Instant windowStart = HourlyRollupAggregator.truncateToHour(windowEnd.minus(rollupsProperties.window()));

        boolean sameInput = hasRun
            && windowEnd.equals(lastRunWindowEnd)
            && activity.version() == lastRunActivityVersion;
        boolean nothingInWindow = hasRun && activity.pendingBound().isBefore(windowStart);
        if (sameInput || nothingInWindow) {
            log.debug("Rollup recompute run skipped: no new telemetry and nothing pending");
            return;
        }

        recalculate(windowStart, windowEnd);
        hasRun = true;
        lastRunActivityVersion = activity.version();
        lastRunWindowEnd = windowEnd;
    }

    public void recalculateAllVehicles() {
        // windowEnd is the start of the CURRENT, still-open hour -- an
        // exclusive upper bound, so the still-in-progress hour is never
        // written as if it were a closed one (design.md: "recalcula las
        // horas cerradas recientes").
        Instant windowEnd = HourlyRollupAggregator.truncateToHour(clock.instant());
        Instant windowStart = HourlyRollupAggregator.truncateToHour(windowEnd.minus(rollupsProperties.window()));
        recalculate(windowStart, windowEnd);
    }

    private void recalculate(Instant windowStart, Instant windowEnd) {
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
