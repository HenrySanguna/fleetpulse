package dev.fleetpulse.api.fleet;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

// Task 2.1 extension: from/to are accepted as plain ISO-8601 instant strings
// (Instant.parse-compatible, e.g. "2026-09-01T00:00:00Z") and parsed here
// rather than bound directly as @RequestParam Instant -- Spring MVC's
// default conversion service has no built-in String-to-Instant converter (it
// only covers LocalDate/LocalDateTime/ZonedDateTime/OffsetDateTime via
// @DateTimeFormat), so binding Instant directly would be silently
// unsupported rather than working by convention. Both are optional: omitting
// them returns the full track (design.md gives no default window).
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/track")
public class VehicleTrackController {

    private final CurrentDispatcher currentDispatcher;
    private final VehicleTrackService trackService;

    public VehicleTrackController(CurrentDispatcher currentDispatcher, VehicleTrackService trackService) {
        this.currentDispatcher = currentDispatcher;
        this.trackService = trackService;
    }

    @GetMapping
    public List<TrackPointResponse> track(
            @PathVariable UUID vehicleId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return trackService.track(vehicleId, dispatcher.organizationId(), parseInstant(from), parseInstant(to));
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ISO-8601 instant: " + value);
        }
    }
}
