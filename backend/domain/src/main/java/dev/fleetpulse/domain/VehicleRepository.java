package dev.fleetpulse.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    // Task 2.1 (04-add-live-map): org-scoped listing for GET /api/fleet/state
    // -- Spring Data derives the join through the `organization` association
    // to its `id` field, the same nested-property pattern used throughout
    // this repository layer's other finder methods.
    List<Vehicle> findByOrganizationId(UUID organizationId);
}
