package dev.fleetpulse.api.geofencing;

// One vertex of the closed ring PostGIS actually stored for a geofence --
// matches TrackPointResponse's own primitive-double convention (always
// present in a response, no null semantics needed here).
public record GeoPointResponse(double lat, double lon) {
}
