package dev.fleetpulse.geocore;

import java.time.Instant;

public record GeoPoint(double lat, double lon, Instant at) {
}
