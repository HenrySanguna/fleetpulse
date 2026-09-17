package dev.fleetpulse.processor.rollups;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;
import dev.fleetpulse.processor.trips.PositionSample;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Task 4.1's own aggregation core: pure and database-free, the same
// pure-logic/JDBC-wiring separation TripSegmenter (pure) vs
// JdbcTripReader/JdbcTripWriter, and AlertSilenceEngine (pure) vs
// JdbcAlertSilenceStateStore, already established. Reuses
// dev.fleetpulse.processor.trips.PositionSample as-is (same shape this
// aggregator needs: recordedAt/lat/lon/speedKmh/ignition) rather than
// declaring a duplicate record -- the same "reuse what already exists"
// convention Geo.distanceMeters reuse already follows, just within-module
// instead of cross-module.
//
// `positions`/`motionUpdates` cover one vehicle's positions from strictly
// before `windowStart` (at most one "anchor" position, supplied only to
// give the very first in-range leg a real `previous` point -- see
// JdbcRollupReader's own query comment) through the recompute window's end,
// replayed through the SAME VehicleMotionStreakTracker bean the live write
// path and trip segmentation already use (VehicleRollupTask wires this the
// same way TripSegmentationTask does).
//
// Bucketing: every POSITION contributes to max_speed_kmh in the hour bucket
// it itself falls in (covers a lone position with no leg at all). Every LEG
// (consecutive position pair) contributes its distance to the hour bucket of
// its END position -- a leg that straddles an hour boundary is attributed
// entirely to the later hour rather than split proportionally; with
// telemetry sampled every few seconds to a few minutes (design.md/spec.md),
// this is a bounded, negligible edge effect, the same class of
// discrete-sampling simplification TripSegmenter already documents for its
// own idle_secs transition-leg exclusion below. A leg counts toward
// moving_secs only when BOTH endpoints are MotionState.MOVING, and toward
// idle_secs only when BOTH endpoints are STOPPED/IDLING (reusing
// TripSegmenter's own "both endpoints must already be classified" rule for
// idle_secs, extended symmetrically to moving_secs); a transition leg (one
// endpoint MOVING, the other not, or either endpoint unclassified/null) is
// counted toward neither, for the exact same reason TripSegmenter excludes
// it from idle_secs: the instant motion actually changed within that
// interval is not observable from discrete samples.
//
// Any bucket whose hour is strictly before `windowStart` is dropped before
// returning: that can only be the anchor position's own hour when it has no
// following in-window position to pair with (e.g. the very first position
// this vehicle has ever recorded, or a vehicle with a data gap spanning past
// windowStart) -- emitting a single-position, context-only bucket there
// would silently understate an hour this run was never asked to recompute.
public final class HourlyRollupAggregator {

    private HourlyRollupAggregator() {
    }

    public static List<HourlyRollupCandidate> aggregate(
        UUID vehicleId,
        UUID organizationId,
        List<PositionSample> positions,
        List<VehicleMotionUpdate> motionUpdates,
        Instant windowStart
    ) {
        if (positions.size() != motionUpdates.size()) {
            throw new IllegalArgumentException("positions and motionUpdates must be parallel lists of the same size");
        }
        if (positions.isEmpty()) {
            return List.of();
        }

        Map<Instant, Bucket> buckets = new LinkedHashMap<>();

        for (PositionSample position : positions) {
            Instant hour = truncateToHour(position.recordedAt());
            Bucket bucket = buckets.computeIfAbsent(hour, h -> new Bucket());
            if (position.speedKmh() != null) {
                bucket.maxSpeedKmh = bucket.maxSpeedKmh == null
                    ? position.speedKmh()
                    : Math.max(bucket.maxSpeedKmh, position.speedKmh());
            }
        }

        for (int i = 1; i < positions.size(); i++) {
            PositionSample previous = positions.get(i - 1);
            PositionSample current = positions.get(i);
            Instant hour = truncateToHour(current.recordedAt());
            Bucket bucket = buckets.get(hour);

            GeoPoint previousPoint = new GeoPoint(previous.lat(), previous.lon(), previous.recordedAt());
            GeoPoint currentPoint = new GeoPoint(current.lat(), current.lon(), current.recordedAt());
            bucket.distanceMeters += Geo.distanceMeters(previousPoint, currentPoint);

            long legSecs = java.time.Duration.between(previous.recordedAt(), current.recordedAt()).toSeconds();
            MotionState previousState = motionUpdates.get(i - 1).motionState();
            MotionState currentState = motionUpdates.get(i).motionState();
            if (isMoving(previousState) && isMoving(currentState)) {
                bucket.movingSecs += legSecs;
            } else if (isNotMoving(previousState) && isNotMoving(currentState)) {
                bucket.idleSecs += legSecs;
            }
        }

        List<HourlyRollupCandidate> result = new ArrayList<>();
        for (Map.Entry<Instant, Bucket> entry : buckets.entrySet()) {
            if (entry.getKey().isBefore(windowStart)) {
                continue;
            }
            Bucket bucket = entry.getValue();
            result.add(new HourlyRollupCandidate(
                vehicleId,
                organizationId,
                entry.getKey(),
                bucket.distanceMeters / 1000.0,
                bucket.movingSecs,
                bucket.idleSecs,
                bucket.maxSpeedKmh
            ));
        }
        return result;
    }

    static Instant truncateToHour(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant();
    }

    private static boolean isMoving(MotionState state) {
        return state == MotionState.MOVING;
    }

    private static boolean isNotMoving(MotionState state) {
        return state == MotionState.STOPPED || state == MotionState.IDLING;
    }

    private static final class Bucket {
        double distanceMeters;
        long movingSecs;
        long idleSecs;
        Double maxSpeedKmh;
    }
}
