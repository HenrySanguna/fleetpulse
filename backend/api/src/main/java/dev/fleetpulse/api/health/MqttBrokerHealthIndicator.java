package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MqttBrokerHealthIndicator implements HealthIndicator {

    private final MqttPahoClientFactory clientFactory;
    private final FleetpulseMqttProperties properties;

    public MqttBrokerHealthIndicator(MqttPahoClientFactory clientFactory, FleetpulseMqttProperties properties) {
        this.clientFactory = clientFactory;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String clientId = "fleetpulse-api-health-" + UUID.randomUUID();
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
