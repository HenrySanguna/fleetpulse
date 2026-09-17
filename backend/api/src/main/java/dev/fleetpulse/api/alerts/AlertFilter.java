package dev.fleetpulse.api.alerts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 3.4: the filter set design.md's "panel con filtros" leaves
// unspecified -- resolved here, documented in tasks.md's own resolution
// note, as the four fields a dispatcher plausibly needs to narrow the
// alerts panel: which alert type(s), which vehicle, whether it has already
// been attended, and a time window. Every field is optional; a null/empty
// value means "no filter on this field", matching VehicleTrackController's
// own optional from/to precedent. types is a list (not a single value) so
// the console's existing "Geocerca" filter chip (alert.model.ts's own
// AlertTypeFilter, which groups geofence_enter/geofence_exit/geofence_dwell
// under one UI category) can request all three geofence types in a single
// request instead of three round trips.
record AlertFilter(List<AlertType> types, UUID vehicleId, Boolean acknowledged, Instant from, Instant to) {
}
