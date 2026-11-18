package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.config.FleetpulseMqttBrowserCredentialProperties;
import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityAdminClient;
import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.domain.MqttCredentialRepository;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BrowserMqttCredentialCapTest {

    private final MosquittoDynamicSecurityAdminClient adminClient = mock(MosquittoDynamicSecurityAdminClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MqttCredentialRepository credentialRepository = mock(MqttCredentialRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final User user = mock(User.class);
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedDispatcher dispatcher = dispatcherOf(userId);

    private final BrowserMqttCredentialService service = new BrowserMqttCredentialService(
        adminClient,
        provider(userRepository),
        provider(credentialRepository),
        new MqttCredentialExpiryTracker(),
        passwordEncoder,
        new FleetpulseMqttBrowserCredentialProperties("ws://broker", Duration.ofMinutes(5), 3));

    private AuthenticatedDispatcher dispatcherOf(UUID id) {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn(UUID.randomUUID());
        when(user.getId()).thenReturn(id);
        when(user.getOrganization()).thenReturn(organization);
        when(user.getRole()).thenReturn(UserRole.DISPATCHER);
        return AuthenticatedDispatcher.from(user);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }

    @Test
    void rejectsWithTooManyRequestsBeforeTouchingTheBrokerWhenTheCapIsReached() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(credentialRepository.countByUserAndExpiresAtAfter(eq(user), any(Instant.class))).thenReturn(3L);

        assertThatThrownBy(() -> service.issueCredentials(dispatcher))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verifyNoInteractions(adminClient);
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void issuesCredentialsWhileBelowTheCap() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(credentialRepository.countByUserAndExpiresAtAfter(eq(user), any(Instant.class))).thenReturn(2L);
        when(passwordEncoder.encode(any())).thenReturn("hash");

        assertThat(service.issueCredentials(dispatcher).username()).startsWith("browser-");

        verify(credentialRepository).save(any());
    }
}
