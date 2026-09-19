package dev.fleetpulse.api.fleet;

import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Task 2.1: combines the org-scoped vehicle list (JPA, VehicleRepository)
// with their latest known state (JDBC, VehicleStateJdbcReader) into one
// snapshot response. A vehicle with no vehicle_state row at all (never
// reported, never seen by the presence consumer either) still appears, with
// every state field null/offline -- the map must show it exists, not hide it.
//
// VehicleRepository is resolved through an ObjectProvider, not injected
// directly, matching BrowserMqttCredentialService/DeviceCredentialService:
// some deployment profiles boot the api module with no DataSource at all.
@Service
public class FleetStateService {

    private final ObjectProvider<VehicleRepository> vehicles;
    private final VehicleStateJdbcReader stateReader;

    FleetStateService(ObjectProvider<VehicleRepository> vehicles, VehicleStateJdbcReader stateReader) {
        this.vehicles = vehicles;
        this.stateReader = stateReader;
    }

    public FleetStateResponse currentState(UUID organizationId) {
        List<Vehicle> orgVehicles = vehicles.getObject().findByOrganizationId(organizationId);
        List<UUID> vehicleIds = orgVehicles.stream().map(Vehicle::getId).toList();
        Map<UUID, VehicleStateRow> states = stateReader.findByVehicleIds(vehicleIds);

        List<VehicleStateResponse> responses = orgVehicles.stream()
            .map(vehicle -> toResponse(vehicle, states.get(vehicle.getId())))
            .toList();

        return new FleetStateResponse(responses);
    }

    private static VehicleStateResponse toResponse(Vehicle vehicle, VehicleStateRow row) {
        if (row == null) {
            return new VehicleStateResponse(
                vehicle.getId(), vehicle.getLabel(), null, null, null, null, false, null, null, null, null, null
            );
        }
        return new VehicleStateResponse(
            vehicle.getId(),
            vehicle.getLabel(),
            row.lat(),
            row.lon(),
            row.recordedAt(),
            row.motionState(),
            row.online(),
            row.destinationLat(),
            row.destinationLon(),
            row.etaSeconds(),
            row.etaMarginSeconds(),
            row.etaCalculatedAt()
        );
    }
}
