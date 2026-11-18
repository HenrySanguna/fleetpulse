package dev.fleetpulse.api.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Task 2.1 (04-add-live-map), extended scope: GET /api/vehicles/{id}/track
// runs Geo.simplifyTrack (geo-core) against the raw `positions` history
// before returning it -- design.md's "la traza histórica llega ya
// simplificada desde el backend". 15m keeps street-level detail (design.md's
// own example, an 8h track collapsing tens of thousands of points to a
// screen-scale-appropriate few) while staying configurable per deployment
// without a code change.
//
// defaultWindow/maxWindow/maxRows bound the query itself (demo hardening):
// an omitted from/to resolves to the last defaultWindow, a window above
// maxWindow is rejected, and at most maxRows of the most recent raw rows are
// read before simplification.
@ConfigurationProperties(prefix = "fleetpulse.map.track")
@Validated
public record FleetpulseTrackProperties(
    @Positive @DefaultValue("15") double simplificationToleranceMeters,
    @NotNull @DefaultValue("2h") Duration defaultWindow,
    @NotNull @DefaultValue("24h") Duration maxWindow,
    @Positive @DefaultValue("5000") int maxRows
) {
}
