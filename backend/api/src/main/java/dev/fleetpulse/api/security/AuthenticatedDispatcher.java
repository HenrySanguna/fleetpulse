package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

// Wraps the domain User as Spring Security's authentication principal. Kept
// deliberately separate from the JPA entity: UserDetails is a security-layer
// contract that belongs to the api module, User is domain state owned by the
// domain module (project.md's module boundary rule).
public final class AuthenticatedDispatcher implements UserDetails {

    private final UUID userId;
    private final UUID organizationId;
    private final String email;
    private final String passwordHash;
    private final UserRole role;
    private final boolean active;

    private AuthenticatedDispatcher(
            UUID userId,
            UUID organizationId,
            String email,
            String passwordHash,
            UserRole role,
            boolean active) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
    }

    public static AuthenticatedDispatcher from(User user) {
        return new AuthenticatedDispatcher(
            user.getId(),
            user.getOrganization().getId(),
            user.getEmail(),
            user.getPasswordHash(),
            user.getRole(),
            user.isActive());
    }

    public UUID userId() {
        return userId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UserRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
