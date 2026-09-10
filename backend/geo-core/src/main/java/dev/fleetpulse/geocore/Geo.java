package dev.fleetpulse.geocore;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

public final class Geo {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private Geo() {
    }

    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double deltaLat = Math.toRadians(b.lat() - a.lat());
        double deltaLon = Math.toRadians(b.lon() - a.lon());

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);
        double h = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

        return EARTH_RADIUS_METERS * c;
    }

    public static double bearingDegrees(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double deltaLon = Math.toRadians(b.lon() - a.lon());

        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));

        return (bearing + 360) % 360;
    }

    public static OptionalDouble speedKmh(GeoPoint a, GeoPoint b) {
        if (!b.at().isAfter(a.at())) {
            return OptionalDouble.empty();
        }

        double seconds = Duration.between(a.at(), b.at()).toNanos() / 1_000_000_000.0;
        double metersPerSecond = distanceMeters(a, b) / seconds;

        return OptionalDouble.of(metersPerSecond * 3.6);
    }

    public static boolean isImplausible(GeoPoint prev, GeoPoint next, double maxKmh) {
        return speedKmh(prev, next).stream().anyMatch(speed -> speed > maxKmh);
    }

    // Douglas-Peucker: recursively drops points whose perpendicular deviation
    // from the current first/last chord is within tolerance, always keeping
    // the chord's own endpoints.
    public static List<GeoPoint> simplifyTrack(List<GeoPoint> points, double toleranceMeters) {
        if (points.size() <= 2) {
            return List.copyOf(points);
        }

        GeoPoint first = points.get(0);
        GeoPoint last = points.get(points.size() - 1);

        int splitIndex = -1;
        double maxDistance = 0;
        for (int i = 1; i < points.size() - 1; i++) {
            double distance = crossTrackDistanceMeters(points.get(i), first, last);
            if (distance > maxDistance) {
                maxDistance = distance;
                splitIndex = i;
            }
        }

        if (maxDistance <= toleranceMeters) {
            return List.of(first, last);
        }

        List<GeoPoint> left = simplifyTrack(points.subList(0, splitIndex + 1), toleranceMeters);
        List<GeoPoint> right = simplifyTrack(points.subList(splitIndex, points.size()), toleranceMeters);

        List<GeoPoint> merged = new ArrayList<>(left.size() + right.size() - 1);
        merged.addAll(left);
        merged.addAll(right.subList(1, right.size()));

        return merged;
    }

    private static double crossTrackDistanceMeters(GeoPoint point, GeoPoint start, GeoPoint end) {
        double angularDistanceStartToPoint = distanceMeters(start, point) / EARTH_RADIUS_METERS;
        double bearingStartToPoint = Math.toRadians(bearingDegrees(start, point));
        double bearingStartToEnd = Math.toRadians(bearingDegrees(start, end));

        double crossTrack = Math.asin(
            Math.sin(angularDistanceStartToPoint) * Math.sin(bearingStartToPoint - bearingStartToEnd)
        ) * EARTH_RADIUS_METERS;

        return Math.abs(crossTrack);
    }
}
