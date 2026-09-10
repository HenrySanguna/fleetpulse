package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// UserRepository is resolved through an ObjectProvider, not injected
// directly, for the same reason as DeactivatedDispatcherSessionFilter: this
// bean is part of Spring Security's auto-configured chain, eagerly created
// during ordinary singleton initialization even in deployment profiles that
// boot with no DataSource at all (see
// ActuatorInfoEndpointTest/OpenApiDocumentPublicationTest and
// DomainRepositoriesAutoConfiguration).
@Service
public class DispatcherUserDetailsService implements UserDetailsService {

    private final ObjectProvider<UserRepository> users;

    public DispatcherUserDetailsService(ObjectProvider<UserRepository> users) {
        this.users = users;
    }

    // Transactional so AuthenticatedDispatcher.from() can still read the
    // lazily-fetched Organization association while the persistence context
    // is open.
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users
            .getObject()
            .findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("No dispatcher with email " + email));
        return AuthenticatedDispatcher.from(user);
    }
}
