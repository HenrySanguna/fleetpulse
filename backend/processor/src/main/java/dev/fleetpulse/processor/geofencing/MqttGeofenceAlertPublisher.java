package dev.fleetpulse.processor.geofencing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

// Task 4.2's real (non-test) GeofenceAlertPublisher: builds the alert's JSON
// payload and hands it, with the topic set per-message via the MQTT_TOPIC
// header (orgId varies per alert, so AlertMqttConfig's handler cannot use a
// single static default topic), to Spring Integration's outbound channel --
// AlertMqttConfig owns the actual broker connection.
@Component
public class MqttGeofenceAlertPublisher implements GeofenceAlertPublisher {

    private final MessageChannel alertOutputChannel;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public MqttGeofenceAlertPublisher(@Qualifier(AlertMqttConfig.ALERT_OUTPUT_CHANNEL) MessageChannel alertOutputChannel) {
        this.alertOutputChannel = alertOutputChannel;
    }

    @Override
    public void publish(GeofenceAlert alert) {
        String topic = "fleet/" + alert.organizationId() + "/alerts";
        String payload = jsonMapper.writeValueAsString(new GeofenceAlertPayload(
            alert.id().toString(),
            alert.vehicleId().toString(),
            alert.geofenceId().toString(),
            alert.type().wireValue(),
            alert.occurredAt().toString()
        ));
        Message<byte[]> message = MessageBuilder
            .withPayload(payload.getBytes(StandardCharsets.UTF_8))
            .setHeader(MqttHeaders.TOPIC, topic)
            .build();
        alertOutputChannel.send(message);
    }
}
