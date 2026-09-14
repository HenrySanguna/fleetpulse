package dev.fleetpulse.processor.presence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

// Task 5.2: parses a presence message (either a device's own online
// announcement or the broker-published will) and applies it to
// vehicle_state.online -- mirrors TelemetryMessageListener's malformed-vs-valid
// counter and catch-log-continue shape, so a malformed presence payload can
// never derail this consumer either, even though no task in this work unit
// requires a dedicated malformed-payload test for presence.
@Component
public class PresenceMessageListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceMessageListener.class);

    private final PresencePayloadParser payloadParser;
    private final VehiclePresenceWriter presenceWriter;
    private final Counter validMessageCounter;
    private final Counter malformedMessageCounter;

    public PresenceMessageListener(
        PresencePayloadParser payloadParser,
        VehiclePresenceWriter presenceWriter,
        MeterRegistry meterRegistry
    ) {
        this.payloadParser = payloadParser;
        this.presenceWriter = presenceWriter;
        this.validMessageCounter = Counter.builder("fleetpulse.presence.messages.valid")
            .description("Presence MQTT messages that passed payload validation")
            .register(meterRegistry);
        this.malformedMessageCounter = Counter.builder("fleetpulse.presence.messages.malformed")
            .description("Presence MQTT messages discarded because they did not match the expected payload contract")
            .register(meterRegistry);
    }

    @ServiceActivator(inputChannel = PresenceMqttConfig.PRESENCE_INPUT_CHANNEL)
    public void onPresenceMessage(Message<String> message) {
        Object topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        try {
            PresenceMessage presenceMessage = payloadParser.parse(topic == null ? null : topic.toString(), message.getPayload());
            validMessageCounter.increment();
            log.debug("Applying presence message for vehicle {}: online={}", presenceMessage.vehicleId(), presenceMessage.online());
            presenceWriter.updateOnlineStatus(presenceMessage.vehicleId(), presenceMessage.online());
        } catch (RuntimeException ex) {
            malformedMessageCounter.increment();
            log.warn("Discarding malformed presence message on topic {}: {}", topic, ex.getMessage());
        }
    }
}
