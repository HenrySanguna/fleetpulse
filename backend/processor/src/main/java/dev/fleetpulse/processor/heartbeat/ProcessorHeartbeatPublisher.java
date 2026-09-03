package dev.fleetpulse.processor.heartbeat;

import dev.fleetpulse.processor.config.FleetpulseHeartbeatProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

// api's HealthIndicator never sees processor die if nothing publishes while
// processor is up: a retained message is the only signal that survives
// between publishes, so api can tell "processor never came back" from a
// single subscribe instead of needing a long-lived connection of its own.
@Component
public class ProcessorHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProcessorHeartbeatPublisher.class);

    // 30s keeps the retained heartbeat comfortably fresher than api's default
    // 90s staleness threshold (FleetpulseHeartbeatProperties): a 3x margin
    // absorbs scheduler jitter and one missed publish without api flipping to
    // DOWN on every minor delay.
    static final long PUBLISH_INTERVAL_MILLIS = 30_000L;

    private final MqttPahoClientFactory clientFactory;
    private final FleetpulseMqttProperties mqttProperties;
    private final FleetpulseHeartbeatProperties heartbeatProperties;

    public ProcessorHeartbeatPublisher(
        MqttPahoClientFactory clientFactory,
        FleetpulseMqttProperties mqttProperties,
        FleetpulseHeartbeatProperties heartbeatProperties
    ) {
        this.clientFactory = clientFactory;
        this.mqttProperties = mqttProperties;
        this.heartbeatProperties = heartbeatProperties;
    }

    @Scheduled(fixedRate = PUBLISH_INTERVAL_MILLIS)
    public void publishHeartbeat() {
        String clientId = "fleetpulse-processor-heartbeat-" + UUID.randomUUID();
        try {
            IMqttClient client = clientFactory.getClientInstance(mqttProperties.brokerUrl(), clientId);
            try {
                client.connect(clientFactory.getConnectionOptions());
                MqttMessage message = new MqttMessage(Instant.now().toString().getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                message.setRetained(true);
                client.publish(heartbeatProperties.topic(), message);
                client.disconnect();
            } finally {
                client.close();
            }
        } catch (MqttException ex) {
            log.warn("Failed to publish processor heartbeat to {}", heartbeatProperties.topic(), ex);
        }
    }
}
