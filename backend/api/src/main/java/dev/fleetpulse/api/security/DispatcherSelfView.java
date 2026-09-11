package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRole;

import java.util.UUID;

public record DispatcherSelfView(UUID id, UUID organizationId, String email, UserRole role) {

    public static DispatcherSelfView from(User user) {
        return new DispatcherSelfView(user.getId(), user.getOrganization().getId(), user.getEmail(), user.getRole());
    }
}
