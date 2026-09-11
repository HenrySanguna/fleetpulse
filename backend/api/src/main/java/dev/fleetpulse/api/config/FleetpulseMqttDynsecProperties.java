package dev.fleetpulse.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Credentials for the Mosquitto dynamic-security plugin's bootstrapped admin
// identity (docker/mosquitto/bootstrap-and-run.sh), used only by
// MosquittoDynamicSecurityAdminClient to drive $CONTROL/dynamic-security/v1.
// Deliberately NOT @Validated/@NotBlank, unlike FleetpulseMqttProperties'
// brokerUrl: that property is required for the whole application to
// function in every profile, this one is only exercised by credential
// issuance/purge (02-add-fleet-auth, tasks 3.1-3.3, 5.1) -- binding it
// unconditionally would force every existing @SpringBootTest in this module
// to supply a value even when it never touches MQTT admin functionality.
// MosquittoDynamicSecurityAdminClient fails loudly at actual connect time
// if either field is blank.
@ConfigurationProperties(prefix = "fleetpulse.mqtt.dynsec")
public record FleetpulseMqttDynsecProperties(String adminUsername, String adminPassword) {
}
