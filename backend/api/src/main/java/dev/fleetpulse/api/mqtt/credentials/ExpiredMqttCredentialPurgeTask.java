package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityAdminClient;
import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityException;
import dev.fleetpulse.domain.MqttCredential;
import dev.fleetpulse.domain.MqttCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

// Task 3.3: reclaims expired browser MQTT credentials -- both the Postgres
// row and the broker-side dynsec client -- so a stale credential cannot be
// used to connect after its expiresAt has passed (spec scenario 6.4).
// Device credentials (MqttCredential.forDevice) have no expiresAt and are
// never touched here; only revocation (task 4.2, a future work unit) ever
// removes those.
//
// MqttCredentialRepository is resolved through ObjectProvider, not injected
// directly: this is an ordinary eagerly instantiated singleton, and some
// deployment profiles boot the api module with no DataSource at all (see
// ActuatorInfoEndpointTest/OpenApiDocumentPublicationTest and
// DomainRepositoriesAutoConfiguration).
@Component
public class ExpiredMqttCredentialPurgeTask {

    private static final Logger log = LoggerFactory.getLogger(ExpiredMqttCredentialPurgeTask.class);
    private static final long PURGE_INTERVAL_MILLIS = 60_000L;

    private final ObjectProvider<MqttCredentialRepository> mqttCredentials;
    private final MosquittoDynamicSecurityAdminClient adminClient;

    public ExpiredMqttCredentialPurgeTask(
            ObjectProvider<MqttCredentialRepository> mqttCredentials,
            MosquittoDynamicSecurityAdminClient adminClient) {
        this.mqttCredentials = mqttCredentials;
        this.adminClient = adminClient;
    }

    @Scheduled(fixedDelay = PURGE_INTERVAL_MILLIS)
    public void purgeExpiredCredentials() {
        MqttCredentialRepository repository = mqttCredentials.getObject();
        List<MqttCredential> expired = repository.findByExpiresAtBefore(Instant.now());
        for (MqttCredential credential : expired) {
            try {
                adminClient.deleteClient(credential.getUsername());
            } catch (MosquittoDynamicSecurityException ex) {
                // Already gone from the broker (e.g. a previous purge cycle
                // revoked it but crashed before deleting the row) must not
                // block reclaiming the Postgres row.
                log.warn("Failed to revoke expired MQTT credential {} on the broker", credential.getUsername(), ex);
            }
            repository.delete(credential);
        }
    }
}
