package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityAdminClient;
import dev.fleetpulse.domain.MqttCredential;
import dev.fleetpulse.domain.MqttCredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExpiredMqttCredentialPurgeTaskTest {

    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

    private final MqttCredentialRepository repository = mock(MqttCredentialRepository.class);
    private final MosquittoDynamicSecurityAdminClient adminClient = mock(MosquittoDynamicSecurityAdminClient.class);
    private final MqttCredentialExpiryTracker tracker = new MqttCredentialExpiryTracker();
    private Instant now = START;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final ExpiredMqttCredentialPurgeTask task =
        new ExpiredMqttCredentialPurgeTask(provider(), adminClient, tracker, clock);

    @SuppressWarnings("unchecked")
    private ObjectProvider<MqttCredentialRepository> provider() {
        ObjectProvider<MqttCredentialRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(repository);
        return provider;
    }

    private void idleRepository() {
        when(repository.findByExpiresAtBefore(any())).thenReturn(List.of());
        when(repository.findEarliestExpiresAt()).thenReturn(Optional.empty());
    }

    @Test
    void queriesOnTheFirstTickAfterStartup() {
        idleRepository();

        task.purgeExpiredCredentials();

        verify(repository, times(1)).findByExpiresAtBefore(START);
    }

    @Test
    void skipsTheRepositoryWhileNothingCanHaveExpired() {
        idleRepository();
        task.purgeExpiredCredentials();

        now = START.plusSeconds(60);
        task.purgeExpiredCredentials();
        task.purgeExpiredCredentials();

        verify(repository, times(1)).findByExpiresAtBefore(any());
        verify(repository, times(1)).findEarliestExpiresAt();
    }

    @Test
    void queriesAgainOnceTheEarliestKnownExpiryHasPassed() {
        when(repository.findByExpiresAtBefore(any())).thenReturn(List.of());
        when(repository.findEarliestExpiresAt()).thenReturn(Optional.of(START.plusSeconds(120)));
        task.purgeExpiredCredentials();

        now = START.plusSeconds(60);
        task.purgeExpiredCredentials();
        verify(repository, times(1)).findByExpiresAtBefore(any());

        now = START.plusSeconds(121);
        task.purgeExpiredCredentials();
        verify(repository, times(1)).findByExpiresAtBefore(now);
    }

    @Test
    void anIssuanceAfterAnIdleStateIsPurgedOnceItExpires() {
        idleRepository();
        task.purgeExpiredCredentials();

        tracker.recordIssued(START.plusSeconds(300));
        MqttCredential credential = mock(MqttCredential.class);
        when(credential.getUsername()).thenReturn("browser-1");

        now = START.plusSeconds(240);
        task.purgeExpiredCredentials();
        verify(repository, times(1)).findByExpiresAtBefore(any());

        now = START.plusSeconds(301);
        when(repository.findByExpiresAtBefore(now)).thenReturn(List.of(credential));
        task.purgeExpiredCredentials();

        verify(adminClient).deleteClient("browser-1");
        verify(repository).delete(credential);
    }

    @Test
    void aFailedRunForcesAQueryOnTheNextTick() {
        idleRepository();
        task.purgeExpiredCredentials();

        tracker.recordIssued(START.plusSeconds(10));
        now = START.plusSeconds(60);
        when(repository.findByExpiresAtBefore(now)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(task::purgeExpiredCredentials).isInstanceOf(IllegalStateException.class);

        now = START.plusSeconds(120);
        idleRepository();
        task.purgeExpiredCredentials();

        verify(repository).findByExpiresAtBefore(now);
    }

    @Test
    void doesNotResolveTheRepositoryWhenSkipping() {
        tracker.startPurge();
        tracker.finishPurge(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<MqttCredentialRepository> provider = mock(ObjectProvider.class);
        ExpiredMqttCredentialPurgeTask skipping =
            new ExpiredMqttCredentialPurgeTask(provider, adminClient, tracker, clock);

        skipping.purgeExpiredCredentials();

        verifyNoInteractions(provider);
        verify(repository, never()).findByExpiresAtBefore(any());
    }
}
