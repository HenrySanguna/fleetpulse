package dev.fleetpulse.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Credentials for the broad-access "internal-services" dynamic-security
// identity (docker/mosquitto/bootstrap-and-run.sh) that this module's own
// ordinary MQTT clients (MqttBrokerHealthIndicator,
// ProcessorHeartbeatHealthIndicator) authenticate as, via the shared
// MqttPahoClientFactory bean in MqttClientFactoryConfig. The dynsec admin
// identity cannot be reused for this: its role only grants access to
// $CONTROL/dynamic-security/# and $SYS/#, not ordinary application topics.
// Deliberately NOT @Validated/@NotBlank -- see FleetpulseMqttDynsecProperties
// for why. When blank, the shared client factory simply omits credentials
// from its connect options.
@ConfigurationProperties(prefix = "fleetpulse.mqtt.service")
public record FleetpulseMqttServiceCredentialsProperties(String username, String password) {
}
