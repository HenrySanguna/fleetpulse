package dev.fleetpulse.api.eta;

import dev.fleetpulse.api.geofencing.GeoPointRequest;
import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 2.1 ("asignacion de destino a un vehiculo"): PUT, not POST -- setting
// a vehicle's single active destination is an idempotent "this vehicle's
// destination IS now X" operation, not creating a new list entry the way
// GeofenceController's POST does. Open to any authenticated dispatcher, NOT
// gated behind hasRole('FLEET_ADMIN') like GeofenceController/
// DeviceCredentialController's own fleet-CONFIGURATION mutations: assigning
// a delivery destination is routine day-to-day dispatch work, the same
// class of action as reading fleet state (FleetStateController), not fleet
// setup. SecurityConfig's default anyRequest().authenticated() already
// covers authentication -- no new permitAll needed. Reuses
// GeofenceController's own GeoPointRequest (identical lat/lon validation
// constraints) rather than a duplicate eta-specific point type.
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/destination")
public class VehicleDestinationController {

    private final CurrentDispatcher currentDispatcher;
    private final VehicleDestinationService destinationService;

    public VehicleDestinationController(CurrentDispatcher currentDispatcher, VehicleDestinationService destinationService) {
        this.currentDispatcher = currentDispatcher;
        this.destinationService = destinationService;
    }

    @PutMapping
    public VehicleDestinationResponse assign(@PathVariable UUID vehicleId, @Valid @RequestBody GeoPointRequest request) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return destinationService.assign(vehicleId, dispatcher.organizationId(), request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID vehicleId) {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        destinationService.clear(vehicleId, dispatcher.organizationId());
    }
}
