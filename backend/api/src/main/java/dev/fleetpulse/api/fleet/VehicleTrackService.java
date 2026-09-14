package dev.fleetpulse.api.fleet;

import dev.fleetpulse.api.config.FleetpulseTrackProperties;
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 2.1 extension: simplified historical track for one vehicle --
// design.md's "la traza histórica llega ya simplificada desde el backend
// (Geo.simplifyTrack en geo-core)". Ownership check mirrors
// DeviceCredentialController#deviceInOwnOrgOrThrow: a vehicle belonging to
// another organization is hidden as 404, not exposed as 403, same intent
// (an HTTP endpoint must not leak cross-org existence).
//
// VehicleRepository is resolved through an ObjectProvider for the same
// DataSource-less-profile reason as FleetStateService.
@Service
public class VehicleTrackService {

    private final ObjectProvider<VehicleRepository> vehicles;
    private final PositionJdbcReader positionReader;
    private final FleetpulseTrackProperties trackProperties;

    VehicleTrackService(
            ObjectProvider<VehicleRepository> vehicles,
            PositionJdbcReader positionReader,
            FleetpulseTrackProperties trackProperties) {
        this.vehicles = vehicles;
        this.positionReader = positionReader;
        this.trackProperties = trackProperties;
    }

    public List<TrackPointResponse> track(UUID vehicleId, UUID organizationId, Instant from, Instant to) {
        Vehicle vehicle = vehicleInOwnOrgOrThrow(vehicleId, organizationId);

        List<GeoPoint> track = positionReader.findTrack(vehicle.getId(), from, to);
        List<GeoPoint> simplified = Geo.simplifyTrack(track, trackProperties.simplificationToleranceMeters());

        return simplified.stream()
            .map(point -> new TrackPointResponse(point.lat(), point.lon(), point.at()))
            .toList();
    }

    private Vehicle vehicleInOwnOrgOrThrow(UUID vehicleId, UUID organizationId) {
        Vehicle vehicle = vehicles.getObject().findById(vehicleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!vehicle.getOrganization().getId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return vehicle;
    }
}
