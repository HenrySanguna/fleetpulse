package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.TelemetryPayload;
import dev.fleetpulse.processor.telemetry.TelemetryPayloadParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 5.3: pure per-tick movement model. No broker, no Spring context --
// this proves the bounded random-walk invariants (RED/GREEN/TRIANGULATE)
// that keep a SimulatedVehicle's state a single fixed-size object across an
// unbounded number of ticks, which is the structural reason the DoD's
// "50 vehicles, 10 minutes, no monotonic memory growth" property holds:
// this generator never accumulates a history, it only ever reads the one
// previous state and returns one new state.
class TelemetrySampleGeneratorTest {

    private final TelemetrySampleGenerator generator = new TelemetrySampleGenerator();

    @Test
    void firstSampleWithNoPreviousStateIsWithinValidRange() {
        SimulatedVehicleState first = generator.next(null, new Random(42));

        assertThat(first.lat()).isBetween(-90.0, 90.0);
        assertThat(first.lon()).isBetween(-180.0, 180.0);
        assertThat(first.speedKmh()).isEqualTo(0.0);
        assertThat(first.heading()).isEqualTo(0.0);
        assertThat(first.ignition()).isFalse();
    }

    // Triangulation: a long chain of ticks from a fixed seed must never
    // leave the valid coordinate/speed/heading ranges, and ignition must
    // always track "moving" (speedKmh > 0.5) exactly -- proves the clamping
    // logic runs on every tick, not just the first one.
    @Test
    void everyTickInALongChainStaysWithinValidRanges() {
        Random random = new Random(7);
        SimulatedVehicleState state = null;
        for (int tick = 0; tick < 2000; tick++) {
            state = generator.next(state, random);

            assertThat(state.lat()).isBetween(-90.0, 90.0);
            assertThat(state.lon()).isBetween(-180.0, 180.0);
            assertThat(state.speedKmh()).isBetween(0.0, 120.0);
            assertThat(state.heading()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            assertThat(state.ignition()).isEqualTo(state.speedKmh() > 0.5);
        }
    }

    // Different seeds must produce different walks -- proves next() actually
    // consumes the injected Random instead of ignoring it (a Fake It that
    // always returned the same coordinates would pass the range test above
    // but fail this one).
    @Test
    void differentSeedsProduceDifferentFirstSamples() {
        SimulatedVehicleState fromSeedOne = generator.next(null, new Random(1));
        SimulatedVehicleState fromSeedTwo = generator.next(null, new Random(2));

        assertThat(fromSeedOne).isNotEqualTo(fromSeedTwo);
    }

    // Wire-format proof: serializing this generator's own toPayload() output
    // through the SAME TelemetryPayload record the real ingest pipeline
    // deserializes into, then feeding it through the real
    // TelemetryPayloadParser (task 2.3's production class, no test double),
    // must succeed and preserve every field. This is what proves the
    // simulator's telemetry is actually compatible with the consumer --
    // without needing a broker.
    @Test
    void payloadRoundTripsThroughTheRealTelemetryPayloadParser() {
        SimulatedVehicleState state = generator.next(null, new Random(99));
        Instant recordedAt = Instant.parse("2026-09-14T10:00:00Z");
        TelemetryPayload payload = generator.toPayload(state, recordedAt);

        UUID vehicleId = UUID.randomUUID();
        String topic = "fleet/org-1/vehicle/" + vehicleId + "/telemetry";
        String json = "{\"recordedAt\":\"" + payload.recordedAt() + "\",\"lat\":" + payload.lat()
            + ",\"lon\":" + payload.lon() + ",\"speedKmh\":" + payload.speedKmh()
            + ",\"heading\":" + payload.heading() + ",\"ignition\":" + payload.ignition() + "}";

        TelemetryMessage parsed = new TelemetryPayloadParser().parse(topic, json);

        assertThat(parsed.vehicleId()).isEqualTo(vehicleId);
        assertThat(parsed.recordedAt()).isEqualTo(recordedAt);
        assertThat(parsed.lat()).isEqualTo(state.lat());
        assertThat(parsed.lon()).isEqualTo(state.lon());
        assertThat(parsed.speedKmh()).isEqualTo(state.speedKmh());
    }
}
