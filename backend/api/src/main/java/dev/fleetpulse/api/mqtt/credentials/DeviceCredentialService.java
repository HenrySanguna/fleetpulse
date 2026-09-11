package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.mqtt.MosquittoDynamicSecurityAdminClient;
import dev.fleetpulse.domain.Device;
import dev.fleetpulse.domain.MqttCredential;
import dev.fleetpulse.domain.MqttCredentialRepository;
import dev.fleetpulse.domain.Vehicle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

// Tasks 4.1/4.2/4.3: issues, revokes and rotates MQTT credentials for an
// existing Device, with an ACL confined to that device's own vehicle
// (design.md's "Dispositivos: credenciales permanentes pero revocables") --
// reuses WU3's MosquittoDynamicSecurityAdminClient exactly as-is for the
// broker side, no second admin client.
//
// MqttCredentialRepository is resolved through an ObjectProvider, not
// injected directly, for the same reason as BrowserMqttCredentialService /
// ExpiredMqttCredentialPurgeTask: this is an ordinary eagerly instantiated
// singleton, and some deployment profiles boot the api module with no
// DataSource at all.
@Service
public class DeviceCredentialService {

    private static final int PASSWORD_BYTES = 24;

    private final MosquittoDynamicSecurityAdminClient adminClient;
    private final ObjectProvider<MqttCredentialRepository> mqttCredentials;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public DeviceCredentialService(
            MosquittoDynamicSecurityAdminClient adminClient,
            ObjectProvider<MqttCredentialRepository> mqttCredentials,
            PasswordEncoder passwordEncoder) {
        this.adminClient = adminClient;
        this.mqttCredentials = mqttCredentials;
        this.passwordEncoder = passwordEncoder;
    }

    // Task 4.1: "alta de dispositivo" -- a device may only be provisioned
    // once; a device that already has an active credential must be rotated
    // instead (task 4.3), not re-provisioned.
    public DeviceCredentialResponse provision(Device device) {
        MqttCredentialRepository repository = mqttCredentials.getObject();
        if (repository.findByDeviceAndRevokedAtIsNull(device).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device already has an active MQTT credential");
        }
        return issueNewCredential(device, repository);
    }

    // Task 4.2 + spec scenario 6.5: deleteClient itself force-disconnects any
    // live broker session under that username (confirmed empirically against
    // a real broker -- Mosquitto logs "disconnected: administrative action"),
    // so no separate disconnect command is needed. The Postgres row is kept
    // with revokedAt set rather than deleted, for audit history; Postgres is
    // never consulted at MQTT connect time (design.md), so this is purely
    // bookkeeping, not a security boundary.
    public void revoke(Device device) {
        MqttCredentialRepository repository = mqttCredentials.getObject();
        MqttCredential credential = activeCredentialOrThrow(device, repository);
        adminClient.deleteClient(credential.getUsername());
        credential.revoke();
        repository.save(credential);
    }

    // Task 4.3: "sin dar de baja el dispositivo" -- retires the current
    // credential (same broker-side deleteClient as revoke(), so a live
    // session under the old credential is also cut) and issues a brand new
    // one for the SAME device. Device itself is only ever read here, never
    // deleted or otherwise deactivated (it has no such flag).
    public DeviceCredentialResponse rotate(Device device) {
        MqttCredentialRepository repository = mqttCredentials.getObject();
        MqttCredential current = activeCredentialOrThrow(device, repository);
        adminClient.deleteClient(current.getUsername());
        current.revoke();
        repository.save(current);
        return issueNewCredential(device, repository);
    }

    private MqttCredential activeCredentialOrThrow(Device device, MqttCredentialRepository repository) {
        return repository.findByDeviceAndRevokedAtIsNull(device)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device has no active MQTT credential"));
    }

    private DeviceCredentialResponse issueNewCredential(Device device, MqttCredentialRepository repository) {
        String username = "device-" + UUID.randomUUID();
        String rawPassword = generatePassword();
        Vehicle vehicle = device.getVehicle();
        UUID orgId = vehicle.getOrganization().getId();
        UUID vehicleId = vehicle.getId();
        // Task 4.1: role is scoped per VEHICLE, not per device -- shared
        // across every credential ever issued for that vehicle (initial
        // provisioning plus each later rotation, and any sibling device on
        // the same vehicle), which is why addPublishAcl/addSubscribeAcl must
        // tolerate re-registering the same rule (see
        // MosquittoDynamicSecurityAdminClient.addRoleAclIfMissing).
        String roleName = "device-" + vehicleId;
        String telemetryTopic = "fleet/" + orgId + "/vehicle/" + vehicleId + "/telemetry";
        String statusTopic = "fleet/" + orgId + "/vehicle/" + vehicleId + "/status";
        String commandTopic = "fleet/" + orgId + "/vehicle/" + vehicleId + "/command";

        adminClient.createClient(username, rawPassword);
        adminClient.createRoleIfMissing(roleName);
        adminClient.addPublishAcl(roleName, telemetryTopic, true);
        adminClient.addPublishAcl(roleName, statusTopic, true);
        adminClient.addSubscribeAcl(roleName, commandTopic, true);
        adminClient.addClientRole(username, roleName);

        MqttCredential credential = MqttCredential.forDevice(username, passwordEncoder.encode(rawPassword), device);
        repository.save(credential);

        return new DeviceCredentialResponse(device.getId(), username, rawPassword);
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
