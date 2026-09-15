package dev.fleetpulse.api.geofencing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

// Shared by POST and PUT: design.md/tasks.md give no reason for a separate
// update shape -- a full replace on PUT needs exactly the same fields as
// create. Structural per-field validation stops at Bean Validation's reach:
// which of vertices/center+radiusMeters is required (and forbidden) depends
// on `shape`, and dwellSecs is only meaningful for ON_DWELL -- mirrors
// processor's GeofenceRule compact constructor (WU4) -- so that cross-field
// check lives in GeofenceService, not here. `vertices` is the OPEN ring (the
// caller does not repeat the first point as the last); the server always
// closes it before building WKT, so "the polygon must be closed" is a
// structural guarantee this API produces, not a client input requirement.
public record GeofenceRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull GeofenceRuleType rule,
    @Positive Integer dwellSecs,
    @NotNull GeofenceShapeType shape,
    @Size(min = 3, max = 500) List<@Valid GeoPointRequest> vertices,
    @Valid GeoPointRequest center,
    @Positive Double radiusMeters
) {
}
