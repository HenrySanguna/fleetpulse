package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Task 2.5 / spec scenario 6.7: a deactivated dispatcher must lose access on
// the very next request, not when their session eventually expires
// (design.md -- this is the entire reason dispatcher sessions are
// server-side and not a self-contained token). The Authentication restored
// from the HTTP session by SecurityContextHolderFilter was captured at login
// time and never re-reads the database on its own, so this filter
// re-validates the `active` flag on every authenticated request.
//
// UserRepository is resolved through an ObjectProvider, not injected
// directly: some deployment profiles boot the api module with the
// DataSource/JPA stack excluded entirely, so no UserRepository bean exists
// at all (see ActuatorInfoEndpointTest/OpenApiDocumentPublicationTest,
// pre-existing before this change, and DomainRepositoriesAutoConfiguration).
// This filter is auto-registered into Spring Security's chain regardless of
// profile; ObjectProvider defers the lookup until an actually-authenticated
// AuthenticatedDispatcher principal is present, which cannot happen in a
// DataSource-less profile in the first place.
@Component
public class DeactivatedDispatcherSessionFilter extends OncePerRequestFilter {

    private final ObjectProvider<UserRepository> users;

    public DeactivatedDispatcherSessionFilter(ObjectProvider<UserRepository> users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedDispatcher dispatcher
                && !stillActive(dispatcher)) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean stillActive(AuthenticatedDispatcher dispatcher) {
        return users.getObject().findById(dispatcher.userId()).map(User::isActive).orElse(false);
    }
}
