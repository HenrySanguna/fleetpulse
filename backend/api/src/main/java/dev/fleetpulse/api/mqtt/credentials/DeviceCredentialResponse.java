package dev.fleetpulse.api.mqtt.credentials;

import java.util.UUID;

// Tasks 4.1/4.3: returned by both provisioning and rotation -- a device
// credential has no wsUrl (devices connect over plain TCP, not from a
// browser) and no expiresAt (long-lived, design.md: "no puede depender de
// renovar credenciales cada hora"), unlike MqttCredentialsResponse.
public record DeviceCredentialResponse(UUID deviceId, String username, String password) {
}
