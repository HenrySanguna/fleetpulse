package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Credentials for the broad-access "internal-services" dynamic-security
// identity (docker/mosquitto/bootstrap-and-run.sh) that this module's own
// ordinary MQTT clients (MqttBrokerHealthIndicator, ProcessorHeartbeatPublisher)
// authenticate as, via the shared MqttPahoClientFactory bean in
// MqttClientFactoryConfig (02-add-fleet-auth, tasks 5.1/5.2). Processor
// never talks to the dynamic-security control topic itself -- that is api
// module's concern only -- so it has no equivalent of api's
// FleetpulseMqttDynsecProperties. Deliberately NOT @Validated/@NotBlank: see
// api module's FleetpulseMqttDynsecProperties for why.
@ConfigurationProperties(prefix = "fleetpulse.mqtt.service")
public record FleetpulseMqttServiceCredentialsProperties(String username, String password) {
}
