package dev.fleetpulse.processor.eta;

import java.time.Instant;
import java.util.UUID;

// Task 2.4 ("publicacion al toplico de la organizacion"): the seam between
// EtaRecalculationDispatcher and however the estimate actually reaches the
// console -- mirrors GeofenceAlertPublisher's own role for alerts.
public interface EtaPublisher {

    void publish(UUID organizationId, UUID vehicleId, EtaEstimate estimate, Instant calculatedAt);
}
