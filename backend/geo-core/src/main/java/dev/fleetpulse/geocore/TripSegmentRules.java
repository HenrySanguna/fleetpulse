package dev.fleetpulse.geocore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Shared, pure trip-segmentation rules: given the ordered per-position
// MotionState timeline for one vehicle and the organization's configured
// stop threshold, decides where a trip starts and ends, and computes each
// span's distance/idle/speed metrics. Previously hand-mirrored in two
// places (processor's TripSegmenter, which persists every CLOSED span, and
// api's InProgressTripCalculator, which only cares whether one is still
// OPEN at the very end of the window it can see) -- unified here (shared
// trip rules change) since both replayed the exact same state machine and
// per-leg metrics loop.
//
// A "not moving" run is a maximal consecutive stretch of positions whose
// MotionState is STOPPED or IDLING (a null MotionState -- a position with
// no speedKmh MotionReplay could not classify -- is treated as neutral/
// MOVING-equivalent so missing data never fragments a trip on its own).
// Once such a run's OWN duration (from its first position to its current
// one) reaches stopThreshold, the trip up to (and not including) that run
// closes: its endIdx is the last MOVING position before the run began. A
// new trip starts once MOVING resumes. A "not moving" run that never
// reaches the threshold never closes anything, so the whole span on either
// side of it stays one continuous trip.
//
// Whatever remains open at the very end of the timeline -- an active trip,
// or a not-yet-threshold-long trailing stop -- is reported as the last
// Segment with a null endIdx, letting each caller decide what to do with it
// (TripSegmenter ignores it; InProgressTripCalculator only wants it). A
// segment (closed or trailing open) spanning fewer than two positions
// carries no distance/duration worth representing, so it is silently
// dropped rather than reported as a zero-length trip.
public final class TripSegmentRules {

    private TripSegmentRules() {
    }

    public static List<Segment> segment(List<MotionState> motionStates, List<Instant> recordedAt, Duration stopThreshold) {
        if (motionStates.size() != recordedAt.size()) {
            throw new IllegalArgumentException("motionStates and recordedAt must be parallel lists of the same size");
        }

        List<Segment> segments = new ArrayList<>();
        Integer tripStartIdx = 0;
        Integer notMovingRunStartIdx = null;
        boolean runAlreadyClosedTrip = false;

        for (int i = 0; i < motionStates.size(); i++) {
            boolean isNotMoving = isNotMoving(motionStates.get(i));

            if (isNotMoving) {
                if (notMovingRunStartIdx == null) {
                    notMovingRunStartIdx = i;
                    runAlreadyClosedTrip = false;
                }
                Duration runDuration = Duration.between(recordedAt.get(notMovingRunStartIdx), recordedAt.get(i));
                if (!runAlreadyClosedTrip && runDuration.compareTo(stopThreshold) >= 0) {
                    // tripStartIdx is provably non-null here: runAlreadyClosedTrip only
                    // ever turns false the moment a fresh not-moving run begins, which
                    // itself only happens right after a MOVING position (or at index 0,
                    // where tripStartIdx still holds its non-null initial value 0) -- and
                    // a MOVING position always leaves tripStartIdx non-null.
                    addIfLongEnough(segments, tripStartIdx, notMovingRunStartIdx - 1);
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

        if (tripStartIdx != null && motionStates.size() - 1 - tripStartIdx >= 1) {
            segments.add(new Segment(tripStartIdx, null));
        }
        return segments;
    }

    // [startIdx, endIdx] inclusive on both ends -- a single-position range
    // carries no distance/duration worth recording, so it is dropped, not
    // reported as a zero-length segment.
    private static void addIfLongEnough(List<Segment> segments, int startIdx, int endIdx) {
        if (endIdx - startIdx >= 1) {
            segments.add(new Segment(startIdx, endIdx));
        }
    }

    // startedAt is a caller-supplied Instant rather than always
    // positions.get(startIdx).recordedAt(): InProgressTripCalculator reports
    // a still-open trip's startedAt as its query window's own start when the
    // real trip start predates what that window can see (clipped), while
    // TripSegmenter always passes positions.get(startIdx).recordedAt() since
    // it never clips.
    public static Metrics metrics(List<PositionSample> positions, List<MotionState> motionStates, int startIdx, int endIdx, Instant startedAt) {
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

            // The leg from `previous` to `current` counts toward idleSecs
            // only when BOTH endpoints are already classified not-moving --
            // not just the leg's start. A transition leg is deliberately
            // left out: the exact instant motion actually changed within
            // that interval is not observable from discrete samples, so it
            // is attributed to neither bucket rather than double-counted.
            if (isNotMoving(motionStates.get(i - 1)) && isNotMoving(motionStates.get(i))) {
                idleSecs += Duration.between(previous.recordedAt(), current.recordedAt()).toSeconds();
            }
        }

        long durationSecs = Duration.between(startedAt, positions.get(endIdx).recordedAt()).toSeconds();
        double distanceKm = distanceMeters / 1000.0;
        // Overall average speed for the whole span, including any
        // sub-threshold idle time within it -- not the average of only the
        // moving legs.
        double avgSpeedKmh = durationSecs > 0 ? distanceKm / (durationSecs / 3600.0) : 0.0;

        return new Metrics(distanceKm, durationSecs, idleSecs, maxSpeedKmh, avgSpeedKmh);
    }

    private static boolean isNotMoving(MotionState state) {
        return state == MotionState.STOPPED || state == MotionState.IDLING;
    }

    // endIdx == null means this segment is still open at the end of the
    // timeline (isOpen()); otherwise it is a closed [startIdx, endIdx] span.
    public record Segment(int startIdx, Integer endIdx) {
        public boolean isOpen() {
            return endIdx == null;
        }
    }

    public record Metrics(double distanceKm, long durationSecs, long idleSecs, double maxSpeedKmh, double avgSpeedKmh) {
    }
}
