package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseHeartbeatProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// processor never exposes HTTP (design.md "Salud"): if it dies, nothing
// breaks visibly and telemetry just stops in silence. A short-lived
// subscribe -- same fail-fast, no-auto-reconnect pattern as
// MqttBrokerHealthIndicator, not a long-lived Spring Integration inbound
// channel adapter -- is enough because a retained message is redelivered
// immediately on SUBSCRIBE, so it never depends on catching a live publish.
@Component
public class ProcessorHeartbeatHealthIndicator implements HealthIndicator {

    // Only needs to absorb broker/network latency after SUBSCRIBE completes,
    // not the publish interval: a retained message, if present, arrives
    // right away.
    private static final Duration RETAINED_MESSAGE_WAIT = Duration.ofSeconds(3);

    private final MqttPahoClientFactory clientFactory;
    private final FleetpulseMqttProperties mqttProperties;
    private final FleetpulseHeartbeatProperties heartbeatProperties;

    public ProcessorHeartbeatHealthIndicator(
        MqttPahoClientFactory clientFactory,
        FleetpulseMqttProperties mqttProperties,
        FleetpulseHeartbeatProperties heartbeatProperties
    ) {
        this.clientFactory = clientFactory;
        this.mqttProperties = mqttProperties;
        this.heartbeatProperties = heartbeatProperties;
    }

    @Override
    public Health health() {
        String clientId = "fleetpulse-api-heartbeat-" + UUID.randomUUID();
        try {
            IMqttClient client = clientFactory.getClientInstance(mqttProperties.brokerUrl(), clientId);
            try {
                CompletableFuture<MqttMessage> retainedMessage = new CompletableFuture<>();
                client.setCallback(retainedMessageCallback(retainedMessage));
                client.connect(clientFactory.getConnectionOptions());
                client.subscribe(heartbeatProperties.topic(), 1);
                Health health = awaitHeartbeat(retainedMessage);
                client.disconnect();
                return health;
            } finally {
                client.close();
            }
        } catch (MqttException ex) {
            return Health.down(ex).build();
        }
    }

    private Health awaitHeartbeat(CompletableFuture<MqttMessage> retainedMessage) {
        MqttMessage message;
        try {
            message = retainedMessage.get(RETAINED_MESSAGE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return Health.down()
                .withDetail("reason", "no retained heartbeat received on " + heartbeatProperties.topic())
                .build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down(ex).build();
        } catch (ExecutionException ex) {
            return Health.down(ex.getCause() != null ? ex.getCause() : ex).build();
        }
        return evaluateFreshness(new String(message.getPayload(), StandardCharsets.UTF_8));
    }

    private Health evaluateFreshness(String rawTimestamp) {
        Instant heartbeatTimestamp;
        try {
            heartbeatTimestamp = Instant.parse(rawTimestamp);
        } catch (DateTimeParseException ex) {
            return Health.down()
                .withDetail("reason", "malformed heartbeat payload")
                .withDetail("payload", rawTimestamp)
                .build();
        }
        Duration age = Duration.between(heartbeatTimestamp, Instant.now());
        if (age.compareTo(heartbeatProperties.stalenessThreshold()) > 0) {
            return Health.down()
                .withDetail("lastHeartbeat", heartbeatTimestamp.toString())
                .withDetail("age", age.toString())
                .withDetail("reason", "processor heartbeat is stale")
                .build();
        }
        return Health.up().withDetail("lastHeartbeat", heartbeatTimestamp.toString()).build();
    }

    private static MqttCallback retainedMessageCallback(CompletableFuture<MqttMessage> retainedMessage) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                retainedMessage.complete(message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        };
    }
}
