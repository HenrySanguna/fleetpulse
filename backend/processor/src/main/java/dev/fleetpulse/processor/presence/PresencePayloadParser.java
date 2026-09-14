package dev.fleetpulse.processor.presence;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Task 5.2: turns a raw MQTT topic + payload into a validated PresenceMessage,
// or throws MalformedPresencePayloadException -- mirrors TelemetryPayloadParser's
// contract so PresenceMessageListener can catch one exception type without
// needing to know which validation step failed.
@Component
public class PresencePayloadParser {

    // Matches the fleet/+/vehicle/+/status subscription filter (PresenceMqttConfig);
    // group 1 is the vehicle segment vehicleId is resolved from.
    private static final Pattern STATUS_TOPIC_PATTERN = Pattern.compile("^fleet/[^/]+/vehicle/([^/]+)/status$");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PresenceMessage parse(String topic, String payload) {
        UUID vehicleId = extractVehicleId(topic);
        PresencePayload raw = deserialize(payload);
        if (raw.online() == null) {
            throw new MalformedPresencePayloadException("online is required");
        }
        return new PresenceMessage(vehicleId, raw.online());
    }

    private UUID extractVehicleId(String topic) {
        if (!StringUtils.hasText(topic)) {
            throw new MalformedPresencePayloadException("Missing MQTT topic header");
        }
        Matcher matcher = STATUS_TOPIC_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            throw new MalformedPresencePayloadException("Topic does not match the presence contract: " + topic);
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            throw new MalformedPresencePayloadException("Topic vehicle segment is not a valid UUID: " + topic, ex);
        }
    }

    private PresencePayload deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, PresencePayload.class);
        } catch (JacksonException ex) {
            throw new MalformedPresencePayloadException("Payload is not valid JSON: " + ex.getMessage(), ex);
        }
    }
}
