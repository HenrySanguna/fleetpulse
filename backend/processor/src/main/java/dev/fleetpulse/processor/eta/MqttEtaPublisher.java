package dev.fleetpulse.processor.eta;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

// Task 2.4's real (non-test) EtaPublisher: builds the ETA's JSON payload and
// hands it, with the per-vehicle topic set via the MQTT_TOPIC header, to
// EtaMqttConfig's own outbound channel -- mirrors MqttGeofenceAlertPublisher's
// shape exactly, publishing to a DIFFERENT channel (EtaMqttConfig owns a
// dedicated connection, see its own class comment for why) and topic
// pattern.
@Component
public class MqttEtaPublisher implements EtaPublisher {

    private final MessageChannel etaOutputChannel;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public MqttEtaPublisher(@Qualifier(EtaMqttConfig.ETA_OUTPUT_CHANNEL) MessageChannel etaOutputChannel) {
        this.etaOutputChannel = etaOutputChannel;
    }

    @Override
    public void publish(UUID organizationId, UUID vehicleId, EtaEstimate estimate, Instant calculatedAt) {
        String topic = "fleet/" + organizationId + "/vehicle/" + vehicleId + "/eta";
        String payload = jsonMapper.writeValueAsString(
            new EtaPayload(estimate.etaSeconds(), estimate.marginSeconds(), calculatedAt.toString())
        );
        Message<byte[]> message = MessageBuilder
            .withPayload(payload.getBytes(StandardCharsets.UTF_8))
            .setHeader(MqttHeaders.TOPIC, topic)
            .build();
        etaOutputChannel.send(message);
    }
}
