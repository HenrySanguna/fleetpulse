package dev.fleetpulse.api.reports;

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
import java.util.UUID;

// Task 4.3: nested under /api/vehicles/{vehicleId}, matching
// VehicleTrackController's own shape (a per-vehicle read scoped by a date
// range) rather than AlertsController's top-level-filterable-list shape --
// "informe de actividad por vehículo" is inherently single-vehicle, unlike
// the alerts panel's org-wide list. from/to use the exact same manual
// Instant.parse-with-400 pattern VehicleTrackController/AlertsController
// already established (Spring MVC has no built-in String-to-Instant
// converter), but are REQUIRED here, not optional: unlike a vehicle's full
// track or the alerts panel's full history (both meaningful with no filter
// at all), an "informe de actividad" is meaningless without a bounded range
// -- the DoD itself only ever talks about "un informe de una semana", never
// an unbounded one. GET is open to any authenticated dispatcher, same
// "routine dispatch/read action, not fleet configuration" reasoning
// AlertsController/VehicleTrackController already documented.
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/activity-report")
public class ActivityReportController {

    private final CurrentDispatcher currentDispatcher;
    private final ActivityReportService activityReportService;

    public ActivityReportController(CurrentDispatcher currentDispatcher, ActivityReportService activityReportService) {
        this.currentDispatcher = currentDispatcher;
        this.activityReportService = activityReportService;
    }

    @GetMapping
    public ActivityReportResponse report(
            @PathVariable UUID vehicleId,
            @RequestParam String from,
            @RequestParam String to) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return activityReportService.report(vehicleId, dispatcher.organizationId(), parseInstant(from), parseInstant(to));
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ISO-8601 instant: " + value);
        }
    }
}
