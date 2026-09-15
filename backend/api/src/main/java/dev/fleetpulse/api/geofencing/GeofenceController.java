package dev.fleetpulse.api.geofencing;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Task 5.2 (backend half, WU6 -- the gap documented in tasks.md's Review
// Workload Forecast, resolved the same way 04-add-live-map's WU2 resolved
// its own missing-track-endpoint gap). GET is open to any authenticated
// dispatcher, matching FleetStateController/VehicleTrackController's
// precedent; POST/PUT/DELETE require FLEET_ADMIN, matching
// DeviceCredentialController's precedent for fleet-configuration mutations.
// SecurityConfig's default anyRequest().authenticated() already covers
// authentication itself -- no new permitAll needed.
@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private final CurrentDispatcher currentDispatcher;
    private final GeofenceService geofenceService;

    public GeofenceController(CurrentDispatcher currentDispatcher, GeofenceService geofenceService) {
        this.currentDispatcher = currentDispatcher;
        this.geofenceService = geofenceService;
    }

    @PostMapping
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public GeofenceResponse create(@Valid @RequestBody GeofenceRequest request) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return geofenceService.create(dispatcher.organizationId(), request);
    }

    @GetMapping
    public List<GeofenceResponse> list() {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return geofenceService.list(dispatcher.organizationId());
    }

    @GetMapping("/{id}")
    public GeofenceResponse get(@PathVariable UUID id) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return geofenceService.get(id, dispatcher.organizationId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    public GeofenceResponse update(@PathVariable UUID id, @Valid @RequestBody GeofenceRequest request) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return geofenceService.update(id, dispatcher.organizationId(), request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        geofenceService.delete(id, dispatcher.organizationId());
    }
}
