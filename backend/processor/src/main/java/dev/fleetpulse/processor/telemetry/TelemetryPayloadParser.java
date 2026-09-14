package dev.fleetpulse.processor.telemetry;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Task 2.3: turns a raw MQTT topic + payload into a validated TelemetryMessage,
// or throws MalformedTelemetryPayloadException. Every failure path throws this
// one exception type so TelemetryMessageListener can catch it without needing
// to know which validation step failed.
@Component
public class TelemetryPayloadParser {

    // Matches the fleet/+/vehicle/+/telemetry subscription filter; group 1 is
    // the vehicle segment the listener resolves vehicleId from.
    private static final Pattern TELEMETRY_TOPIC_PATTERN = Pattern.compile("^fleet/[^/]+/vehicle/([^/]+)/telemetry$");
    private static final double MIN_LATITUDE = -90.0;
    private static final double MAX_LATITUDE = 90.0;
    private static final double MIN_LONGITUDE = -180.0;
    private static final double MAX_LONGITUDE = 180.0;
    private static final double MIN_HEADING = 0.0;
    private static final double MAX_HEADING = 360.0;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public TelemetryMessage parse(String topic, String payload) {
        UUID vehicleId = extractVehicleId(topic);
        TelemetryPayload raw = deserialize(payload);
        return validate(vehicleId, raw);
    }

    private UUID extractVehicleId(String topic) {
        if (!StringUtils.hasText(topic)) {
            throw new MalformedTelemetryPayloadException("Missing MQTT topic header");
        }
        Matcher matcher = TELEMETRY_TOPIC_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            throw new MalformedTelemetryPayloadException("Topic does not match the telemetry contract: " + topic);
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            throw new MalformedTelemetryPayloadException("Topic vehicle segment is not a valid UUID: " + topic, ex);
        }
    }

    private TelemetryPayload deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, TelemetryPayload.class);
        } catch (JacksonException ex) {
            throw new MalformedTelemetryPayloadException("Payload is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private TelemetryMessage validate(UUID vehicleId, TelemetryPayload raw) {
        Instant recordedAt = parseRecordedAt(raw.recordedAt());
        double lat = requireInRange(raw.lat(), MIN_LATITUDE, MAX_LATITUDE, "lat");
        double lon = requireInRange(raw.lon(), MIN_LONGITUDE, MAX_LONGITUDE, "lon");
        if (raw.speedKmh() != null && raw.speedKmh() < 0) {
            throw new MalformedTelemetryPayloadException("speedKmh must not be negative: " + raw.speedKmh());
        }
        if (raw.heading() != null && (raw.heading() < MIN_HEADING || raw.heading() >= MAX_HEADING)) {
            throw new MalformedTelemetryPayloadException("heading must be within [0, 360): " + raw.heading());
        }
        return new TelemetryMessage(vehicleId, recordedAt, lat, lon, raw.speedKmh(), raw.heading(), raw.ignition());
    }

    private Instant parseRecordedAt(String recordedAt) {
        if (!StringUtils.hasText(recordedAt)) {
            throw new MalformedTelemetryPayloadException("recordedAt is required");
        }
        try {
            return Instant.parse(recordedAt);
        } catch (DateTimeParseException ex) {
            throw new MalformedTelemetryPayloadException("recordedAt is not a valid ISO-8601 instant: " + recordedAt, ex);
        }
    }

    private double requireInRange(Double value, double min, double max, String fieldName) {
        if (value == null) {
            throw new MalformedTelemetryPayloadException(fieldName + " is required");
        }
        if (value < min || value > max) {
            throw new MalformedTelemetryPayloadException(fieldName + " must be within [" + min + ", " + max + "]: " + value);
        }
        return value;
    }
}
