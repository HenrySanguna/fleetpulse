package dev.fleetpulse.processor.alerts;

import dev.fleetpulse.processor.geofencing.AlertMqttConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

// Tasks 3.2/3.3's real (non-test) AlertPublisher: reuses AlertMqttConfig's
// SAME outbound channel/QoS-2 connection MqttGeofenceAlertPublisher already
// established (dev.fleetpulse.processor.geofencing) -- design.md: "todas las
// alertas ... llegan a la consola por el mismo topico MQTT con QoS 2". No new
// MqttPahoClientFactory bean, no new ACL registration: this is genuinely the
// same fleet/{orgId}/alerts topic geofence alerts already publish to, just
// for the two new alert types this work unit adds. Cross-package reuse of
// AlertMqttConfig (still declared in the geofencing package, which built it
// first) is deliberate: moving that class would touch every one of
// geofencing's own already-passing dual-container tests for no functional
// gain, when reusing its already-public ALERT_OUTPUT_CHANNEL constant is
// sufficient.
@Component
public class MqttAlertPublisher implements AlertPublisher {

    private final MessageChannel alertOutputChannel;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public MqttAlertPublisher(@Qualifier(AlertMqttConfig.ALERT_OUTPUT_CHANNEL) MessageChannel alertOutputChannel) {
        this.alertOutputChannel = alertOutputChannel;
    }

    @Override
    public void publish(Alert alert) {
        String topic = "fleet/" + alert.organizationId() + "/alerts";
        String payload = jsonMapper.writeValueAsString(new AlertPayload(
            alert.id().toString(),
            alert.vehicleId().toString(),
            alert.type().wireValue(),
            alert.context() == null ? null : alert.context().toString(),
            alert.occurredAt().toString()
        ));
        Message<byte[]> message = MessageBuilder
            .withPayload(payload.getBytes(StandardCharsets.UTF_8))
            .setHeader(MqttHeaders.TOPIC, topic)
            .build();
        alertOutputChannel.send(message);
    }
}
