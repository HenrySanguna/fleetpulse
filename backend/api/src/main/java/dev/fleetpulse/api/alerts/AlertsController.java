package dev.fleetpulse.api.alerts;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

// Task 3.4 ("Panel de alertas en la consola con filtros y marcado como
// atendida"). GET is open to any authenticated dispatcher, matching
// GeofenceController.list()/FleetStateController's own precedent -- viewing
// alerts is routine day-to-day dispatch work, not fleet configuration.
// PATCH .../acknowledge follows the same "routine dispatch action, not
// FLEET_ADMIN-gated" reasoning VehicleDestinationController's own PUT/DELETE
// already documented. SecurityConfig's default anyRequest().authenticated()
// already covers authentication -- no new permitAll needed.
//
// Endpoint verb decision (design.md/tasks.md leave the exact shape open, no
// existing PATCH precedent in this codebase to follow): PATCH
// /api/alerts/{id}/acknowledge, not PUT with a full replacement body or a
// nested POST .../acknowledgements collection -- "marcar como atendida" is a
// single-field state transition on an existing resource (acknowledged:
// false -> true), the textbook case for HTTP PATCH, and a dedicated
// sub-resource path (mirroring GeofenceController's flat /{id} shape rather
// than inventing a new collection) keeps the request bodyless, matching this
// action's own AlertsPageComponent trigger (a plain button click, no form
// data to submit).
@RestController
@RequestMapping("/api/alerts")
public class AlertsController {

    private final CurrentDispatcher currentDispatcher;
    private final AlertsService alertsService;

    public AlertsController(CurrentDispatcher currentDispatcher, AlertsService alertsService) {
        this.currentDispatcher = currentDispatcher;
        this.alertsService = alertsService;
    }

    @GetMapping
    public List<AlertResponse> list(
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        AlertFilter filter = new AlertFilter(parseTypes(type), vehicleId, acknowledged, parseInstant(from), parseInstant(to));
        return alertsService.list(dispatcher.organizationId(), filter);
    }

    @PatchMapping("/{id}/acknowledge")
    public AlertResponse acknowledge(@PathVariable UUID id) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return alertsService.acknowledge(id, dispatcher.organizationId(), dispatcher.userId());
    }

    private static List<AlertType> parseTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(AlertType::fromQueryValue).toList();
    }

    // Same manual Instant.parse-with-400 shape as VehicleTrackController's
    // own from/to parsing -- Spring MVC's default conversion service has no
    // built-in String-to-Instant converter.
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
