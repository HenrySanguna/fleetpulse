package dev.fleetpulse.api.alerts;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// Task 3.4: org-scoping (matching every other api module service's own
// precedent -- GeofenceService/VehicleDestinationService) before ever
// touching `alerts`. An alert in another organization is hidden as 404, not
// exposed as 403, the same cross-org-leak precedent every org-scoped
// endpoint in this codebase already follows.
@Service
public class AlertsService {

    private final JdbcAlertsRepository repository;

    AlertsService(JdbcAlertsRepository repository) {
        this.repository = repository;
    }

    public List<AlertResponse> list(UUID organizationId, AlertFilter filter) {
        return repository.findFiltered(organizationId, filter);
    }

    // PATCH .../acknowledge is idempotent by construction: acknowledge()'s
    // UPDATE has no WHERE acknowledged = false guard, so calling it twice on
    // an already-attended alert is a harmless no-op that still returns 200
    // with the same acknowledged=true row, matching ordinary PATCH semantics
    // (unlike VehicleDestinationService.clear()'s DELETE no-op, this mutation
    // always has a real row to report back).
    public AlertResponse acknowledge(UUID id, UUID organizationId) {
        boolean updated = repository.acknowledge(id, organizationId);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return repository.findById(id, organizationId)
            .orElseThrow(() -> new IllegalStateException("Alert not found immediately after acknowledge: " + id));
    }
}
