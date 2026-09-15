package dev.fleetpulse.api.geofencing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// Boxed Double (not primitive double), matching VehicleStateResponse's own
// choice: a missing lat/lon field in the request JSON must deserialize to
// null and trip @NotNull, not silently default to 0.0.
public record GeoPointRequest(
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon
) {
}
