package dev.fleetpulse.geocore;

public record CircleGeofence(GeoPoint center, double radiusMeters) {

    public boolean contains(GeoPoint point) {
        return Geo.distanceMeters(center, point) <= radiusMeters;
    }
}
