package dev.fleetpulse.api.geofencing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Never the raw geofences row. `vertices` is the closed ring PostGIS
// actually stored (task 1.3: a circle is stored as its ST_Buffer'd polygon,
// so vertices here is that buffered ring, not the original center/radius --
// the console, WU7, reads this the same way for both shapes, one code path,
// matching tasks 2.1-2.3's "un solo camino de evaluacion" convention already
// established for evaluation). The ring is returned CLOSED (first point
// repeated as the last), faithful to what PostGIS actually stores; a caller
// that only wants distinct vertices can drop the last entry.
public record GeofenceResponse(
    UUID id,
    String name,
    GeofenceRuleType rule,
    Integer dwellSecs,
    List<GeoPointResponse> vertices,
    Instant createdAt
) {
}
