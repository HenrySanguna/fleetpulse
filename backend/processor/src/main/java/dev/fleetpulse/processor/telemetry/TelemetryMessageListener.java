package dev.fleetpulse.processor.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

// Task 2.3: a malformed payload is discarded and counted, never allowed to
// propagate out of this handler, so the consumer keeps processing later
// messages (test 6.9). No persistence happens yet -- filtering implausible
// positions (task 2.4) and buffering for batch writes belong to a later work
// unit; a valid message is only counted and logged here.
@Component
public class TelemetryMessageListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetryMessageListener.class);

    private final TelemetryPayloadParser payloadParser;
    private final Counter validMessageCounter;
    private final Counter malformedMessageCounter;

    public TelemetryMessageListener(TelemetryPayloadParser payloadParser, MeterRegistry meterRegistry) {
        this.payloadParser = payloadParser;
        this.validMessageCounter = Counter.builder("fleetpulse.telemetry.messages.valid")
            .description("Telemetry MQTT messages that passed payload validation")
            .register(meterRegistry);
        this.malformedMessageCounter = Counter.builder("fleetpulse.telemetry.messages.malformed")
            .description("Telemetry MQTT messages discarded because they did not match the expected payload contract")
            .register(meterRegistry);
    }

    @ServiceActivator(inputChannel = TelemetryMqttConfig.TELEMETRY_INPUT_CHANNEL)
    public void onTelemetryMessage(Message<String> message) {
        Object topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        try {
            TelemetryMessage telemetryMessage = payloadParser.parse(topic == null ? null : topic.toString(), message.getPayload());
            validMessageCounter.increment();
            log.debug("Accepted telemetry message for vehicle {} recorded at {}",
                telemetryMessage.vehicleId(), telemetryMessage.recordedAt());
        } catch (RuntimeException ex) {
            malformedMessageCounter.increment();
            log.warn("Discarding malformed telemetry message on topic {}: {}", topic, ex.getMessage());
        }
    }
}
