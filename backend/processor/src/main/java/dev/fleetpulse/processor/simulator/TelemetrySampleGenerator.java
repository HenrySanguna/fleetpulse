package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.processor.telemetry.TelemetryPayload;

import java.time.Instant;
import java.util.Random;

// Task 5.3: pure per-tick movement model for one simulated vehicle. Each
// tick nudges lat/lon/heading/speed a small, clamped step from the previous
// state instead of computing anything from an accumulated history --
// SimulatedVehicle only ever keeps the single SimulatedVehicleState this
// returns, so this generator's own contribution to the simulator's memory
// footprint is O(1) per vehicle regardless of how many ticks have run.
public final class TelemetrySampleGenerator {

    static final double MIN_LAT = -90.0;
    static final double MAX_LAT = 90.0;
    static final double MIN_LON = -180.0;
    static final double MAX_LON = 180.0;
    static final double MAX_STEP_DEGREES = 0.01;
    static final double MIN_SPEED_KMH = 0.0;
    static final double MAX_SPEED_KMH = 120.0;
    static final double MAX_SPEED_STEP_KMH = 10.0;
    static final double MAX_HEADING_STEP_DEGREES = 15.0;
    static final double MOVING_SPEED_THRESHOLD_KMH = 0.5;

    public SimulatedVehicleState next(SimulatedVehicleState previous, Random random) {
        if (previous == null) {
            return seed(random);
        }
        double lat = clamp(previous.lat() + step(random, MAX_STEP_DEGREES), MIN_LAT, MAX_LAT);
        double lon = clamp(previous.lon() + step(random, MAX_STEP_DEGREES), MIN_LON, MAX_LON);
        double speedKmh = clamp(previous.speedKmh() + step(random, MAX_SPEED_STEP_KMH), MIN_SPEED_KMH, MAX_SPEED_KMH);
        double heading = normalizeHeading(previous.heading() + step(random, MAX_HEADING_STEP_DEGREES));
        return new SimulatedVehicleState(lat, lon, speedKmh, heading, speedKmh > MOVING_SPEED_THRESHOLD_KMH);
    }

    public TelemetryPayload toPayload(SimulatedVehicleState state, Instant recordedAt) {
        return new TelemetryPayload(
            recordedAt.toString(), state.lat(), state.lon(), state.speedKmh(), state.heading(), state.ignition()
        );
    }

    private SimulatedVehicleState seed(Random random) {
        double lat = MIN_LAT + random.nextDouble() * (MAX_LAT - MIN_LAT);
        double lon = MIN_LON + random.nextDouble() * (MAX_LON - MIN_LON);
        return new SimulatedVehicleState(lat, lon, 0.0, 0.0, false);
    }

    private static double step(Random random, double magnitude) {
        return (random.nextDouble() * 2 - 1) * magnitude;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeHeading(double heading) {
        double normalized = heading % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }
}
