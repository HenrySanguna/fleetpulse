package dev.fleetpulse.api.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Task 2.3: resolves the authenticated dispatcher -- and therefore their
// orgId -- from the current request's security context. Any controller that
// needs per-request org scoping goes through this instead of reading
// SecurityContextHolder directly.
@Component
public class CurrentDispatcher {

    public AuthenticatedDispatcher require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedDispatcher dispatcher)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated dispatcher in this request");
        }
        return dispatcher;
    }
}
