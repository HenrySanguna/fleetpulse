package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.config.FleetpulseMqttBrowserCredentialProperties;
import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityAdminClient;
import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.domain.MqttCredential;
import dev.fleetpulse.domain.MqttCredentialRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

// Tasks 3.1/3.2: issues an ephemeral, session-scoped MQTT credential for the
// browser console and registers a read-only ACL limited to the dispatcher's
// own organization (fleet/{orgId}/#) -- design.md's "Credenciales MQTT del
// navegador" flow, and the mechanism behind spec scenario 6.1 (the single
// most important test of this whole change).
//
// UserRepository/MqttCredentialRepository are resolved through
// ObjectProvider, not injected directly: this is an ordinary eagerly
// instantiated singleton, and some deployment profiles boot the api module
// with no DataSource at all (see ActuatorInfoEndpointTest/
// OpenApiDocumentPublicationTest and DomainRepositoriesAutoConfiguration).
@Service
public class BrowserMqttCredentialService {

    private static final int PASSWORD_BYTES = 24;

    private final MosquittoDynamicSecurityAdminClient adminClient;
    private final ObjectProvider<UserRepository> users;
    private final ObjectProvider<MqttCredentialRepository> mqttCredentials;
    private final PasswordEncoder passwordEncoder;
    private final FleetpulseMqttBrowserCredentialProperties browserProperties;
    private final SecureRandom random = new SecureRandom();

    public BrowserMqttCredentialService(
            MosquittoDynamicSecurityAdminClient adminClient,
            ObjectProvider<UserRepository> users,
            ObjectProvider<MqttCredentialRepository> mqttCredentials,
            PasswordEncoder passwordEncoder,
            FleetpulseMqttBrowserCredentialProperties browserProperties) {
        this.adminClient = adminClient;
        this.users = users;
        this.mqttCredentials = mqttCredentials;
        this.passwordEncoder = passwordEncoder;
        this.browserProperties = browserProperties;
    }

    public MqttCredentialsResponse issueCredentials(AuthenticatedDispatcher dispatcher) {
        User user = users.getObject().findById(dispatcher.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        String username = "browser-" + UUID.randomUUID();
        String rawPassword = generatePassword();
        Instant expiresAt = Instant.now().plus(browserProperties.credentialTtl());
        String roleName = "dispatcher-read-" + dispatcher.organizationId();
        // Task 3.2: read-only, org-scoped -- the wildcard's second level is
        // the orgId itself (design.md: "no es cosmético"), never a broader
        // pattern that would leak another organization's telemetry.
        String topicFilter = "fleet/" + dispatcher.organizationId() + "/#";

        adminClient.createClient(username, rawPassword);
        adminClient.createRoleIfMissing(roleName);
        adminClient.addSubscribeAcl(roleName, topicFilter, true);
        adminClient.addClientRole(username, roleName);

        MqttCredential credential = MqttCredential
            .forDispatcherSession(username, passwordEncoder.encode(rawPassword), user, expiresAt);
        mqttCredentials.getObject().save(credential);

        return new MqttCredentialsResponse(username, rawPassword, browserProperties.wsUrl(), expiresAt);
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
