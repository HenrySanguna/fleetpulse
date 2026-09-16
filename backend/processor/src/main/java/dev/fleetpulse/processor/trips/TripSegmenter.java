package dev.fleetpulse.processor.trips;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.telemetry.VehicleMotionUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Tasks 1.2/1.4 (design.md "Segmentacion de viajes"): "un viaje empieza
// cuando el vehiculo pasa a moving y termina cuando permanece en stopped
// mas de un umbral" is MotionState vocabulary (geo-core's own MOVING /
// IDLING / STOPPED, change 01, already replayed live by
// VehicleMotionStreakTracker since change 03) -- not a gap in telemetry
// reporting. Resolved here, documented per this project's "note
// deviations, don't silently freelance" convention since design.md never
// spells out the mechanism: TripSegmentationTask replays each vehicle's
// window of positions through the SAME VehicleMotionStreakTracker bean the
// live write path already uses (same MotionConfig thresholds/debounce), so
// a trip's boundaries are decided from the exact MOVING/IDLING/STOPPED
// classification vehicle_state.motion_state would show if this window were
// being processed live -- not a second, parallel speed-threshold heuristic.
// TripSegmenter itself only consumes the resulting per-position MotionState
// timeline; it adds zero new geo-core code, reusing Geo.distanceMeters (task
// 1.4's explicit ask) for per-leg distance the same way WU2 of
// 05-add-geofencing reused FenceTransition.from() as-is.
//
// A "not moving" run is a maximal consecutive stretch of positions whose
// MotionState is STOPPED or IDLING (a null MotionState -- a position with
// no speedKmh VehicleMotionStreakTracker could not classify -- is treated
// as neutral/MOVING-equivalent so missing data never fragments a trip on
// its own). Once such a run's OWN duration (from its first position to its
// current one) reaches the organization's configured stop threshold, the
// trip up to (and not including) that run is closed: `endedAt` is the last
// MOVING position before the run began, matching design.md's "termina
// cuando permanece parado mas del umbral" -- the qualifying stop itself is
// excluded from the trip's own span, not appended to it. A new trip starts
// once MOVING resumes. A "not moving" run that never reaches the threshold
// (a red light, a brief queue) never closes anything, so the whole span on
// either side of it stays one continuous trip -- its duration instead
// counts toward that trip's own idle_secs metric (task 1.4).
//
// The TRAILING span at the very end of `positions` -- whether the vehicle
// is still MOVING, or is mid-stop but has not yet reached the threshold --
// is deliberately never emitted as a TripCandidate: this run cannot yet
// know whether that stop will end up qualifying, or whether the vehicle
// resumes moving again soon. Leaving it unemitted is what makes
// reprocessing idempotent (task 1.5): the next run's watermark stays at
// the last CLOSED trip's ended_at, so it naturally re-reads and
// re-evaluates that same trailing span once more real time (design.md's
// deliberate processing delay) has passed.
public final class TripSegmenter {

    private TripSegmenter() {
    }

    public static List<TripCandidate> segment(
        UUID vehicleId,
        UUID organizationId,
        List<PositionSample> positions,
        List<VehicleMotionUpdate> motionUpdates,
        Duration stopThreshold
    ) {
        if (positions.size() != motionUpdates.size()) {
            throw new IllegalArgumentException("positions and motionUpdates must be parallel lists of the same size");
        }

        List<TripCandidate> trips = new ArrayList<>();
        Integer tripStartIdx = 0;
        Integer notMovingRunStartIdx = null;
        boolean runAlreadyClosedTrip = false;

        for (int i = 0; i < positions.size(); i++) {
            boolean isNotMoving = isNotMoving(motionUpdates.get(i).motionState());

            if (isNotMoving) {
                if (notMovingRunStartIdx == null) {
                    notMovingRunStartIdx = i;
                    runAlreadyClosedTrip = false;
                }
                Duration runDuration = Duration.between(
                    positions.get(notMovingRunStartIdx).recordedAt(), positions.get(i).recordedAt()
                );
                if (!runAlreadyClosedTrip && runDuration.compareTo(stopThreshold) >= 0) {
                    if (tripStartIdx != null && notMovingRunStartIdx > tripStartIdx) {
                        addTrip(trips, vehicleId, organizationId, positions, motionUpdates, tripStartIdx, notMovingRunStartIdx - 1);
                    }
                    tripStartIdx = null;
                    runAlreadyClosedTrip = true;
                }
            } else {
                notMovingRunStartIdx = null;
                if (tripStartIdx == null) {
                    tripStartIdx = i;
                }
            }
        }
        // Whatever remains open here (an active tripStartIdx, or a
        // not-yet-threshold-long trailing stop) is intentionally never
        // emitted -- see class comment.
        return trips;
    }

    private static boolean isNotMoving(MotionState state) {
        return state == MotionState.STOPPED || state == MotionState.IDLING;
    }

    // [startIdx, endIdx] is inclusive on both ends. A single-position range
    // carries no distance/duration worth recording, so it is dropped, not
    // persisted as a zero-length trip -- its position is simply re-read
    // (and re-dropped, harmlessly) on a future run, since the watermark
    // never advances past a trip that was never inserted.
    private static void addTrip(
        List<TripCandidate> trips,
        UUID vehicleId,
        UUID organizationId,
        List<PositionSample> positions,
        List<VehicleMotionUpdate> motionUpdates,
        int startIdx,
        int endIdx
    ) {
        if (endIdx - startIdx < 1) {
            return;
        }

        double distanceMeters = 0.0;
        long idleSecs = 0;
        double maxSpeedKmh = 0.0;

        for (int i = startIdx + 1; i <= endIdx; i++) {
            PositionSample previous = positions.get(i - 1);
            PositionSample current = positions.get(i);
            GeoPoint previousPoint = new GeoPoint(previous.lat(), previous.lon(), previous.recordedAt());
            GeoPoint currentPoint = new GeoPoint(current.lat(), current.lon(), current.recordedAt());
            distanceMeters += Geo.distanceMeters(previousPoint, currentPoint);

            if (previous.speedKmh() != null) {
                maxSpeedKmh = Math.max(maxSpeedKmh, previous.speedKmh());
            }
            if (current.speedKmh() != null) {
                maxSpeedKmh = Math.max(maxSpeedKmh, current.speedKmh());
            }

            // The leg from `previous` to `current` counts toward idle_secs
            // only when BOTH endpoints are already classified not-moving --
            // not just the leg's start. A transition leg (the last MOVING
            // reading into the first STOPPED/IDLING one, or the reverse) is
            // deliberately left out of idle_secs: the exact instant motion
            // actually changed within that interval is not observable from
            // discrete samples, so it is attributed to neither bucket
            // rather than double-counted into idle time on top of the
            // fully-bounded not-moving legs that follow it.
            if (isNotMoving(motionUpdates.get(i - 1).motionState()) && isNotMoving(motionUpdates.get(i).motionState())) {
                idleSecs += Duration.between(previous.recordedAt(), current.recordedAt()).toSeconds();
            }
        }

        Instant startedAt = positions.get(startIdx).recordedAt();
        Instant endedAt = positions.get(endIdx).recordedAt();
        long durationSecs = Duration.between(startedAt, endedAt).toSeconds();
        double distanceKm = distanceMeters / 1000.0;
        // Overall average speed for the whole trip span, including any
        // sub-threshold idle time within it (a common, simple definition of
        // "average speed for a trip") -- not the average of only the
        // moving legs. Neither design.md nor proposal.md states which one
        // is required.
        double avgSpeedKmh = durationSecs > 0 ? distanceKm / (durationSecs / 3600.0) : 0.0;

        trips.add(new TripCandidate(
            vehicleId, organizationId, startedAt, endedAt, distanceKm, durationSecs, idleSecs, maxSpeedKmh, avgSpeedKmh
        ));
    }
}
