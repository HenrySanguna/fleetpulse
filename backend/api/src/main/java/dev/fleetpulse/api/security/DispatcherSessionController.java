package dev.fleetpulse.api.security;

import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

// UserRepository is resolved through an ObjectProvider, not injected
// directly, for the same reason as
// DeactivatedDispatcherSessionFilter/DispatcherUserDetailsService: this
// controller is an ordinary eagerly-instantiated singleton, and some
// deployment profiles boot the api module with no DataSource at all (see
// ActuatorInfoEndpointTest/OpenApiDocumentPublicationTest and
// DomainRepositoriesAutoConfiguration).
@RestController
@RequestMapping("/api/dispatchers")
public class DispatcherSessionController {

    private final CurrentDispatcher currentDispatcher;
    private final ObjectProvider<UserRepository> users;

    public DispatcherSessionController(CurrentDispatcher currentDispatcher, ObjectProvider<UserRepository> users) {
        this.currentDispatcher = currentDispatcher;
        this.users = users;
    }

    // Task 2.3: proves org resolution works per request -- every call re-reads
    // the security context set up by that request's session, never a value
    // cached across requests.
    @GetMapping("/me")
    public DispatcherSelfView me() {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return DispatcherSelfView.from(loadOrThrow(dispatcher.userId()));
    }

    // Task 2.4: only a fleet admin may look up ANOTHER dispatcher's info, and
    // task 2.3's org resolution scopes it to the requester's own
    // organization -- otherwise this ordinary REST endpoint would itself
    // become the kind of cross-org data leak design.md worries about for
    // the broker ACL, just over HTTP instead of MQTT. A 404 (not 403) hides
    // whether a dispatcher with that id exists at all in another org.
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    public DispatcherSelfView findDispatcher(@PathVariable UUID id) {
        AuthenticatedDispatcher requester = currentDispatcher.require();
        User target = loadOrThrow(id);
        if (!target.getOrganization().getId().equals(requester.organizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return DispatcherSelfView.from(target);
    }

    private User loadOrThrow(UUID id) {
        return users.getObject().findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
