package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.processor.telemetry.TelemetryPayload;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

// Task 5.3 (movement model reworked by task 5.9): pure per-tick movement
// model for one simulated vehicle. Each tick nudges speed/heading a small,
// clamped step from the previous state and then displaces the position by
// speedKmh * tickInterval along heading -- SimulatedVehicle only ever keeps
// the single SimulatedVehicleState this returns, so this generator's own
// contribution to the simulator's memory footprint is O(1) per vehicle
// regardless of how many ticks have run.
//
// Task 5.9: previously seeded and stepped lat/lon independently and
// uniformly over the whole globe, with a per-tick step unrelated to the
// reported speed (~2000 km/h implied) -- vehicles landed in oceans and their
// telemetry was implausible by construction. Now vehicles are confined to a
// configurable SimulationBounds (SIMULATOR_BOUNDS, defaulting to the whole
// globe for backward compatibility) and moved by an actual
// distance = speedKmh * tickInterval, so implied speed always matches
// reported speed and stays comfortably under
// FleetpulseTelemetryImplausibilityProperties' default 300 km/h threshold.
public final class TelemetrySampleGenerator {

    // Speeds are deliberately far below the implausibility filter's default
    // 300 km/h threshold (FleetpulseTelemetryImplausibilityProperties): the
    // generator moves vehicles by exactly speedKmh * tickInterval, so any
    // reported speed is also the implied speed -- these are just realistic
    // urban driving numbers, not a safety margin against the filter.
    static final double MIN_SPEED_KMH = 0.0;
    static final double MOVING_FLOOR_KMH = 15.0;
    static final double MAX_SPEED_KMH = 80.0;
    static final double MAX_SPEED_STEP_KMH = 6.0;
    static final double CRUISE_SPEED_MIN_KMH = 20.0;
    static final double CRUISE_SPEED_MAX_KMH = 45.0;
    static final double MAX_HEADING_STEP_DEGREES = 15.0;
    static final double MOVING_SPEED_THRESHOLD_KMH = 0.5;

    // Per-tick transition probabilities for the moving/idling/stopped
    // behaviour mix (VehicleMotionStreakTracker / geo-core's MotionDetector
    // derive Moving/Idling/Stopped from sustained speed + ignition, not any
    // field this generator stores directly -- ignition true at speed ~0 is
    // what produces Idling, ignition false is Stopped). Kept deliberately
    // simple: no per-vehicle "regime" state is stored, it is re-derived each
    // tick from the previous (speedKmh, ignition) pair.
    static final double STOP_CHANCE_PER_TICK = 0.01;
    static final double RESUME_CHANCE_PER_TICK = 0.03;
    static final double ENGINE_OFF_CHANCE_PER_TICK = 0.05;

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MIN_LATITUDE_COSINE = 0.01;

    private final SimulationBounds bounds;
    private final Duration tickInterval;

    public TelemetrySampleGenerator() {
        this(SimulationBounds.GLOBAL, DeviceSimulatorSettings.DEFAULT_TELEMETRY_INTERVAL);
    }

    public TelemetrySampleGenerator(SimulationBounds bounds, Duration tickInterval) {
        this.bounds = bounds;
        this.tickInterval = tickInterval;
    }

    public SimulatedVehicleState next(SimulatedVehicleState previous, Random random) {
        if (previous == null) {
            return seed(random);
        }
        SpeedAndIgnition motion = evolveSpeedAndIgnition(previous.speedKmh(), previous.ignition(), random);
        double heading = normalizeHeading(previous.heading() + step(random, MAX_HEADING_STEP_DEGREES));
        DisplacedPosition position = moveWithinBounds(previous.lat(), previous.lon(), heading, motion.speedKmh());
        return new SimulatedVehicleState(position.lat(), position.lon(), motion.speedKmh(), position.heading(), motion.ignition());
    }

    public TelemetryPayload toPayload(SimulatedVehicleState state, Instant recordedAt) {
        return new TelemetryPayload(
            recordedAt.toString(), state.lat(), state.lon(), state.speedKmh(), state.heading(), state.ignition()
        );
    }

    private SimulatedVehicleState seed(Random random) {
        double lat = bounds.minLat() + random.nextDouble() * (bounds.maxLat() - bounds.minLat());
        double lon = bounds.minLon() + random.nextDouble() * (bounds.maxLon() - bounds.minLon());
        return new SimulatedVehicleState(lat, lon, 0.0, 0.0, false);
    }

    // MOVING: normal random-walk cruising, floored above MOVING_FLOOR_KMH so
    // a vehicle only stops via the explicit STOP_CHANCE_PER_TICK roll below,
    // never by drifting down to ~0 through ordinary jitter.
    // IDLING (speed ~0, ignition true): engine running, either resumes
    // cruising or switches off into STOPPED.
    // STOPPED (speed ~0, ignition false): only resumes cruising.
    private SpeedAndIgnition evolveSpeedAndIgnition(double previousSpeedKmh, boolean previousIgnition, Random random) {
        boolean wasMoving = previousSpeedKmh > MOVING_SPEED_THRESHOLD_KMH;
        if (wasMoving) {
            if (random.nextDouble() < STOP_CHANCE_PER_TICK) {
                return new SpeedAndIgnition(0.0, true);
            }
            double speed = clamp(previousSpeedKmh + step(random, MAX_SPEED_STEP_KMH), MOVING_FLOOR_KMH, MAX_SPEED_KMH);
            return new SpeedAndIgnition(speed, true);
        }
        if (previousIgnition) {
            if (random.nextDouble() < RESUME_CHANCE_PER_TICK) {
                return new SpeedAndIgnition(cruiseSpeed(random), true);
            }
            if (random.nextDouble() < ENGINE_OFF_CHANCE_PER_TICK) {
                return new SpeedAndIgnition(0.0, false);
            }
            return new SpeedAndIgnition(0.0, true);
        }
        if (random.nextDouble() < RESUME_CHANCE_PER_TICK) {
            return new SpeedAndIgnition(cruiseSpeed(random), true);
        }
        return new SpeedAndIgnition(0.0, false);
    }

    private static double cruiseSpeed(Random random) {
        return CRUISE_SPEED_MIN_KMH + random.nextDouble() * (CRUISE_SPEED_MAX_KMH - CRUISE_SPEED_MIN_KMH);
    }

    // Displaces by speed*time along heading; if that would leave bounds,
    // reflects heading off whichever wall(s) were crossed (mirrors the
    // north-south component on a latitude wall, the east-west component on
    // a longitude wall) and re-displaces from the reflected heading instead
    // of clamping straight into the wall. The final clamp is only a safety
    // net for float edge cases at a corner.
    private DisplacedPosition moveWithinBounds(double lat, double lon, double heading, double speedKmh) {
        double[] candidate = displace(lat, lon, heading, speedKmh);
        boolean crossedLat = candidate[0] < bounds.minLat() || candidate[0] > bounds.maxLat();
        boolean crossedLon = candidate[1] < bounds.minLon() || candidate[1] > bounds.maxLon();
        if (crossedLat || crossedLon) {
            if (crossedLat) {
                heading = normalizeHeading(180 - heading);
            }
            if (crossedLon) {
                heading = normalizeHeading(360 - heading);
            }
            candidate = displace(lat, lon, heading, speedKmh);
        }
        double newLat = clamp(candidate[0], bounds.minLat(), bounds.maxLat());
        double newLon = clamp(candidate[1], bounds.minLon(), bounds.maxLon());
        return new DisplacedPosition(newLat, newLon, heading);
    }

    private double[] displace(double lat, double lon, double headingDegrees, double speedKmh) {
        double distanceKm = speedKmh * tickInterval.toMillis() / 3_600_000.0;
        double angularDistance = distanceKm / EARTH_RADIUS_KM;
        double headingRad = Math.toRadians(headingDegrees);
        double latCosine = Math.max(Math.cos(Math.toRadians(lat)), MIN_LATITUDE_COSINE);
        double deltaLat = Math.toDegrees(angularDistance * Math.cos(headingRad));
        double deltaLon = Math.toDegrees(angularDistance * Math.sin(headingRad) / latCosine);
        return new double[] {lat + deltaLat, lon + deltaLon};
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

    private record SpeedAndIgnition(double speedKmh, boolean ignition) {
    }

    private record DisplacedPosition(double lat, double lon, double heading) {
    }
}
