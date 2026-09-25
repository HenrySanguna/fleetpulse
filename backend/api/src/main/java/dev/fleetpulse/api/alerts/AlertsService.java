package dev.fleetpulse.api.alerts;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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

    // PATCH .../acknowledge is idempotent by construction: an already-
    // acknowledged alert is returned as-is (its original acknowledgedAt/
    // acknowledgedBy preserved), a harmless no-op that still returns 200,
    // matching ordinary PATCH semantics (unlike VehicleDestinationService
    // .clear()'s DELETE no-op, this mutation always has a real row to report
    // back). T9 (prod QA): who/when is only ever set on the FIRST
    // acknowledgement -- a later call, even by a different dispatcher, must
    // never overwrite it, so this checks `acknowledged` here before writing
    // rather than trusting the UPDATE's own row count alone (0 rows updated
    // is otherwise ambiguous between "already acknowledged" and "no such
    // alert in this organization").
    public AlertResponse acknowledge(UUID id, UUID organizationId, UUID dispatcherId) {
        AlertResponse existing = repository.findById(id, organizationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (existing.acknowledged()) {
            return existing;
        }
        repository.acknowledge(id, organizationId, dispatcherId, Instant.now());
        return repository.findById(id, organizationId)
            .orElseThrow(() -> new IllegalStateException("Alert not found immediately after acknowledge: " + id));
    }
}
