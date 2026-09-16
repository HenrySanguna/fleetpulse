package dev.fleetpulse.api.eta;

import dev.fleetpulse.api.geofencing.GeoPointRequest;
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

// Task 2.1: org-scoping (matching GeofenceService/DeviceCredentialController's
// own precedent) before ever touching vehicle_destinations. A vehicle in
// another organization is hidden as 404, not exposed as 403 -- the same
// cross-org-leak precedent every other org-scoped endpoint in this codebase
// already follows.
@Service
public class VehicleDestinationService {

    private final ObjectProvider<VehicleRepository> vehicles;
    private final JdbcVehicleDestinationRepository repository;

    VehicleDestinationService(ObjectProvider<VehicleRepository> vehicles, JdbcVehicleDestinationRepository repository) {
        this.vehicles = vehicles;
        this.repository = repository;
    }

    public VehicleDestinationResponse assign(UUID vehicleId, UUID organizationId, GeoPointRequest request) {
        vehicleInOwnOrgOrThrow(vehicleId, organizationId);
        repository.upsert(vehicleId, organizationId, request.lat(), request.lon(), Instant.now());
        return repository.findByVehicleId(vehicleId, organizationId)
            .orElseThrow(() -> new IllegalStateException("Destination not found immediately after assignment: " + vehicleId));
    }

    public void clear(UUID vehicleId, UUID organizationId) {
        vehicleInOwnOrgOrThrow(vehicleId, organizationId);
        // Clearing an already-unassigned vehicle is a harmless no-op, not a
        // 404 -- DELETE's own idempotent semantics, matching
        // GeofenceService.delete()'s stricter precedent would be wrong here
        // since a geofence is a caller-known resource id while "no
        // destination assigned" is an ordinary, expected steady state.
        repository.delete(vehicleId, organizationId);
    }

    private void vehicleInOwnOrgOrThrow(UUID vehicleId, UUID organizationId) {
        Vehicle vehicle = vehicles.getObject().findById(vehicleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!vehicle.getOrganization().getId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
