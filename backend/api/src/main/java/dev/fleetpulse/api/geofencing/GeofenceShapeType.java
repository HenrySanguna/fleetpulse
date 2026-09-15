package dev.fleetpulse.api.geofencing;

// Task 1.3's storage convention (area is always a Polygon, even for a
// circle) is a persistence detail -- the create/update request still needs
// to know whether the caller drew a polygon or a circle, since a circle is
// only ever a center + radius on the wire, buffered into a polygon
// server-side (JdbcGeofenceRepository), never sent as a polygon the client
// approximated itself.
public enum GeofenceShapeType {
    POLYGON,
    CIRCLE
}
