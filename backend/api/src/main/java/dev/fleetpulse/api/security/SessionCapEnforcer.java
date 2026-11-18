package dev.fleetpulse.api.security;

import dev.fleetpulse.api.config.FleetpulseSessionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

// A shared demo account can be logged into from many clients at once; each
// login persists a row in SPRING_SESSION, so without a cap it is a cheap way
// to grow that table. Deliberately not Spring Security's maximumSessions: that
// needs a SessionRegistry, which Spring Session's JDBC store does not back.
//
// The repository is resolved through an ObjectProvider because profiles that
// exclude the DataSource have no session repository bean.
@Component
public class SessionCapEnforcer {

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;
    private final FleetpulseSessionProperties properties;

    public SessionCapEnforcer(
            ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions,
            FleetpulseSessionProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    // Runs inside the login success handler, before the new session is
    // committed, so the current session is excluded and the remaining
    // (cap - 1) slots go to the newest of the user's existing sessions.
    public void enforce(String principalName, String currentSessionId) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository == null) {
            return;
        }
        List<? extends Session> others = repository.findByPrincipalName(principalName).values().stream()
            .filter(session -> !session.getId().equals(currentSessionId))
            .sorted(Comparator.comparing(Session::getCreationTime).reversed())
            .toList();
        int keep = properties.maxSessionsPerUser() - 1;
        for (int i = keep; i < others.size(); i++) {
            repository.deleteById(others.get(i).getId());
        }
    }
}
