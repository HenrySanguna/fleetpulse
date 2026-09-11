package dev.fleetpulse.processor.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryPayloadParserTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String VALID_TOPIC = "fleet/org-1/vehicle/" + VEHICLE_ID + "/telemetry";

    private final TelemetryPayloadParser parser = new TelemetryPayloadParser();

    @Test
    void parsesAWellFormedPayloadIntoATelemetryMessage() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":40.4,"lon":-3.7,"speedKmh":55.0,"heading":180.0,"ignition":true}
            """;

        TelemetryMessage message = parser.parse(VALID_TOPIC, payload);

        assertThat(message.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(message.recordedAt()).isEqualTo(Instant.parse("2026-09-11T10:00:00Z"));
        assertThat(message.lat()).isEqualTo(40.4);
        assertThat(message.lon()).isEqualTo(-3.7);
        assertThat(message.speedKmh()).isEqualTo(55.0);
        assertThat(message.heading()).isEqualTo(180.0);
        assertThat(message.ignition()).isTrue();
    }

    @Test
    void parsesAPayloadWithoutOptionalFields() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":40.4,"lon":-3.7}
            """;

        TelemetryMessage message = parser.parse(VALID_TOPIC, payload);

        assertThat(message.speedKmh()).isNull();
        assertThat(message.heading()).isNull();
        assertThat(message.ignition()).isNull();
    }

    @Test
    void rejectsPayloadThatIsNotValidJson() {
        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, "not-json-at-all"))
            .isInstanceOf(MalformedTelemetryPayloadException.class);
    }

    @Test
    void rejectsTopicThatDoesNotMatchTheTelemetryContract() {
        assertThatThrownBy(() -> parser.parse("fleet/org-1/vehicle/" + VEHICLE_ID + "/status", "{}"))
            .isInstanceOf(MalformedTelemetryPayloadException.class);
    }

    @Test
    void rejectsTopicWhoseVehicleSegmentIsNotAValidUuid() {
        assertThatThrownBy(() -> parser.parse("fleet/org-1/vehicle/not-a-uuid/telemetry", "{}"))
            .isInstanceOf(MalformedTelemetryPayloadException.class);
    }

    @Test
    void rejectsPayloadMissingRecordedAt() {
        String payload = """
            {"lat":40.4,"lon":-3.7}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("recordedAt");
    }

    @Test
    void rejectsPayloadWithUnparseableRecordedAt() {
        String payload = """
            {"recordedAt":"not-a-timestamp","lat":40.4,"lon":-3.7}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("recordedAt");
    }

    @Test
    void rejectsPayloadMissingLatitude() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lon":-3.7}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("lat");
    }

    @Test
    void rejectsLatitudeOutOfRange() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":95.0,"lon":-3.7}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("lat");
    }

    @Test
    void rejectsLongitudeOutOfRange() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":40.4,"lon":-190.0}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("lon");
    }

    @Test
    void rejectsNegativeSpeed() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":40.4,"lon":-3.7,"speedKmh":-1.0}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("speedKmh");
    }

    @Test
    void rejectsHeadingOutOfRange() {
        String payload = """
            {"recordedAt":"2026-09-11T10:00:00Z","lat":40.4,"lon":-3.7,"heading":360.0}
            """;

        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, payload))
            .isInstanceOf(MalformedTelemetryPayloadException.class)
            .hasMessageContaining("heading");
    }
}
