package dev.fleetpulse.api.geofencing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// Task 5.2 (backend half, WU6): org-scoping (matching every other api
// endpoint's convention -- FleetStateController/VehicleTrackController) and
// cross-field request validation Bean Validation's per-field reach cannot
// express, delegating actual persistence and geometry construction to
// JdbcGeofenceRepository. A geofence in another organization is hidden as
// 404, not exposed as 403 -- matching DeviceCredentialController's own
// precedent for the same class of cross-org leak.
@Service
public class GeofenceService {

    private final JdbcGeofenceRepository repository;

    GeofenceService(JdbcGeofenceRepository repository) {
        this.repository = repository;
    }

    public GeofenceResponse create(UUID organizationId, GeofenceRequest request) {
        validateShapeAndRuleFields(request);
        UUID id = repository.insert(organizationId, request);
        return repository.findById(id, organizationId)
            .orElseThrow(() -> new IllegalStateException("Geofence not found immediately after insert: " + id));
    }

    public List<GeofenceResponse> list(UUID organizationId) {
        return repository.findAllByOrganization(organizationId);
    }

    public GeofenceResponse get(UUID id, UUID organizationId) {
        return repository.findById(id, organizationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public GeofenceResponse update(UUID id, UUID organizationId, GeofenceRequest request) {
        validateShapeAndRuleFields(request);
        boolean updated = repository.update(id, organizationId, request);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return repository.findById(id, organizationId)
            .orElseThrow(() -> new IllegalStateException("Geofence not found immediately after update: " + id));
    }

    // See JdbcGeofenceRepository.SOFT_DELETE_SQL's comment: this is a soft
    // delete (is_active = false), not a hard row removal -- documented
    // deviation from a literal "DELETE" reading, required by the
    // vehicle_fence_state/alerts foreign keys having no ON DELETE CASCADE
    // anywhere in this schema.
    public void delete(UUID id, UUID organizationId) {
        boolean deleted = repository.softDelete(id, organizationId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    // Mirrors processor's GeofenceRule compact constructor (WU4) for the
    // dwellSecs/rule conditional-relevance pairing, and additionally
    // enforces that a request only ever carries the fields relevant to its
    // own shape (a polygon request must not also carry center/radiusMeters,
    // and vice versa) -- structural checks List<@Valid GeoPointRequest>'s
    // @Size(min = 3) alone cannot express, since `vertices` is legitimately
    // null for a CIRCLE request.
    private void validateShapeAndRuleFields(GeofenceRequest request) {
        if (request.shape() == GeofenceShapeType.POLYGON) {
            if (request.vertices() == null || request.vertices().size() < 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A polygon geofence requires at least 3 vertices");
            }
            if (request.center() != null || request.radiusMeters() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A polygon geofence must not specify center or radiusMeters");
            }
        } else {
            if (request.center() == null || request.radiusMeters() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A circle geofence requires center and radiusMeters");
            }
            if (request.vertices() != null && !request.vertices().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A circle geofence must not specify vertices");
            }
        }
        if (request.rule() == GeofenceRuleType.ON_DWELL && request.dwellSecs() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dwellSecs is required for the on_dwell rule");
        }
        if (request.rule() != GeofenceRuleType.ON_DWELL && request.dwellSecs() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dwellSecs must be omitted unless rule is on_dwell");
        }
    }
}
