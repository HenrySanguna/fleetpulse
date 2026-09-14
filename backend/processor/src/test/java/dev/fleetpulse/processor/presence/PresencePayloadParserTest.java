package dev.fleetpulse.processor.presence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresencePayloadParserTest {

    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String VALID_TOPIC = "fleet/org-1/vehicle/" + VEHICLE_ID + "/status";

    private final PresencePayloadParser parser = new PresencePayloadParser();

    @Test
    void parsesAnOnlineAnnouncementIntoAPresenceMessage() {
        PresenceMessage message = parser.parse(VALID_TOPIC, "{\"online\":true}");

        assertThat(message.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(message.online()).isTrue();
    }

    @Test
    void parsesAnOfflineTestamentIntoAPresenceMessage() {
        PresenceMessage message = parser.parse(VALID_TOPIC, "{\"online\":false}");

        assertThat(message.online()).isFalse();
    }

    @Test
    void rejectsPayloadThatIsNotValidJson() {
        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, "not-json-at-all"))
            .isInstanceOf(MalformedPresencePayloadException.class);
    }

    @Test
    void rejectsPayloadMissingOnline() {
        assertThatThrownBy(() -> parser.parse(VALID_TOPIC, "{}"))
            .isInstanceOf(MalformedPresencePayloadException.class)
            .hasMessageContaining("online");
    }

    @Test
    void rejectsTopicThatDoesNotMatchTheStatusContract() {
        assertThatThrownBy(() -> parser.parse("fleet/org-1/vehicle/" + VEHICLE_ID + "/telemetry", "{\"online\":true}"))
            .isInstanceOf(MalformedPresencePayloadException.class);
    }

    @Test
    void rejectsTopicWhoseVehicleSegmentIsNotAValidUuid() {
        assertThatThrownBy(() -> parser.parse("fleet/org-1/vehicle/not-a-uuid/status", "{\"online\":true}"))
            .isInstanceOf(MalformedPresencePayloadException.class);
    }

    @Test
    void rejectsMissingTopic() {
        assertThatThrownBy(() -> parser.parse(null, "{\"online\":true}"))
            .isInstanceOf(MalformedPresencePayloadException.class);
    }
}
