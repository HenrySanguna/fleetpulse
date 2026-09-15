package dev.fleetpulse.processor.health;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MqttBrokerHealthIndicator implements HealthIndicator {

    private final MqttPahoClientFactory clientFactory;
    private final FleetpulseMqttProperties properties;

    // Presence (WU8) and geofencing alerts (05, WU4) each added their own
    // MqttPahoClientFactory bean alongside this one and telemetry's, so
    // autowiring by type alone is ambiguous (4 candidates) -- this health
    // check was never meant to share any of those domain-specific
    // connections, it needs its own short-lived probe connection, which is
    // exactly what MqttClientFactoryConfig's generic bean is for.
    public MqttBrokerHealthIndicator(
        @Qualifier("mqttPahoClientFactory") MqttPahoClientFactory clientFactory,
        FleetpulseMqttProperties properties
    ) {
        this.clientFactory = clientFactory;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String clientId = "fleetpulse-processor-health-" + UUID.randomUUID();
        try {
            IMqttClient client = clientFactory.getClientInstance(properties.brokerUrl(), clientId);
            try {
                client.connect(clientFactory.getConnectionOptions());
                boolean connected = client.isConnected();
                client.disconnect();
                return (connected ? Health.up() : Health.down())
                    .withDetail("brokerUrl", properties.brokerUrl())
                    .build();
            } finally {
                client.close();
            }
        } catch (MqttException ex) {
            return Health.down(ex).withDetail("brokerUrl", properties.brokerUrl()).build();
        }
    }
}
