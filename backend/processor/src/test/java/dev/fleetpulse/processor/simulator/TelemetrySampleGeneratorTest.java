package dev.fleetpulse.processor.simulator;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
import dev.fleetpulse.processor.telemetry.TelemetryImplausibilityFilter;
import dev.fleetpulse.processor.telemetry.TelemetryMessage;
import dev.fleetpulse.processor.telemetry.TelemetryPayload;
import dev.fleetpulse.processor.telemetry.TelemetryPayloadParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
//
// Task 5.9: also proves the movement rework -- vehicles stay inside a
// configured SimulationBounds instead of roaming the whole globe, and the
// implied speed between consecutive positions matches the reported speed
// closely enough to pass the REAL TelemetryImplausibilityFilter with prod's
// default 300 km/h threshold, instead of the old independent lat/lon jitter
// that implied ~2000 km/h.
class TelemetrySampleGeneratorTest {

    private static final SimulationBounds MADRID = new SimulationBounds(40.35, -3.80, 40.52, -3.58);
    private static final Duration PROD_TICK_INTERVAL = Duration.ofMillis(2000);

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
    // leave the valid coordinate/speed/heading ranges. Ignition must be true
    // whenever the vehicle is actually moving, but -- unlike before task
    // 5.9 -- ignition can also be true at speedKmh ~ 0 (Idling: engine on,
    // vehicle stopped), so the converse no longer holds.
    @Test
    void everyTickInALongChainStaysWithinValidRanges() {
        Random random = new Random(7);
        SimulatedVehicleState state = null;
        for (int tick = 0; tick < 2000; tick++) {
            state = generator.next(state, random);

            assertThat(state.lat()).isBetween(-90.0, 90.0);
            assertThat(state.lon()).isBetween(-180.0, 180.0);
            assertThat(state.speedKmh()).isBetween(0.0, TelemetrySampleGenerator.MAX_SPEED_KMH);
            assertThat(state.heading()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            if (state.speedKmh() > TelemetrySampleGenerator.MOVING_SPEED_THRESHOLD_KMH) {
                assertThat(state.ignition()).isTrue();
            }
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

    // Task 5.9: with a configured Madrid SimulationBounds, both the seeded
    // position and every subsequent tick's position (including after a
    // boundary reflection) must stay inside the box -- the DoD this whole
    // task exists for, checked over many vehicles and many ticks from fixed
    // seeds.
    @Test
    void staysWithinConfiguredBoundsAcrossManyVehiclesAndTicks() {
        TelemetrySampleGenerator madridGenerator = new TelemetrySampleGenerator(MADRID, PROD_TICK_INTERVAL);
        for (int vehicle = 0; vehicle < 10; vehicle++) {
            Random random = new Random(1000 + vehicle);
            SimulatedVehicleState state = null;
            for (int tick = 0; tick < 3000; tick++) {
                state = madridGenerator.next(state, random);

                assertThat(state.lat()).isBetween(MADRID.minLat(), MADRID.maxLat());
                assertThat(state.lon()).isBetween(MADRID.minLon(), MADRID.maxLon());
            }
        }
    }

    // Task 5.9: the implied speed between two consecutive positions
    // (haversine distance / tick interval, the same math the real
    // TelemetryImplausibilityFilter uses via Geo.isImplausible) must be
    // consistent with the reported speedKmh and stay well under
    // FleetpulseTelemetryImplausibilityProperties' default 300 km/h --
    // proving the old ~2000 km/h implied-speed bug is gone.
    @Test
    void impliedSpeedBetweenConsecutivePositionsMatchesReportedSpeedAndStaysPlausible() {
        TelemetrySampleGenerator madridGenerator = new TelemetrySampleGenerator(MADRID, PROD_TICK_INTERVAL);
        Random random = new Random(42);
        Instant recordedAt = Instant.parse("2026-09-24T10:00:00Z");
        SimulatedVehicleState previous = madridGenerator.next(null, random);
        GeoPoint previousPoint = new GeoPoint(previous.lat(), previous.lon(), recordedAt);

        for (int tick = 0; tick < 3000; tick++) {
            recordedAt = recordedAt.plus(PROD_TICK_INTERVAL);
            SimulatedVehicleState next = madridGenerator.next(previous, random);
            GeoPoint nextPoint = new GeoPoint(next.lat(), next.lon(), recordedAt);

            double impliedSpeedKmh = Geo.speedKmh(previousPoint, nextPoint).orElse(0.0);

            assertThat(impliedSpeedKmh).isCloseTo(next.speedKmh(), Offset.offset(0.5));
            assertThat(impliedSpeedKmh).isLessThan(300.0);

            previous = next;
            previousPoint = nextPoint;
        }
    }

    // Task 5.9: feeds the generator's own output through the REAL
    // TelemetryImplausibilityFilter (prod's default 300 km/h threshold,
    // same as FleetpulseTelemetryImplausibilityProperties' @DefaultValue) --
    // not a re-implementation of its math -- to prove demo telemetry is
    // never silently discarded in production.
    @Test
    void generatedSamplesAreNeverRejectedByTheRealImplausibilityFilter() {
        TelemetrySampleGenerator madridGenerator = new TelemetrySampleGenerator(MADRID, PROD_TICK_INTERVAL);
        TelemetryImplausibilityFilter filter = new TelemetryImplausibilityFilter(
            new FleetpulseTelemetryImplausibilityProperties(300.0), new SimpleMeterRegistry()
        );
        UUID vehicleId = UUID.randomUUID();
        Random random = new Random(2026);
        Instant recordedAt = Instant.parse("2026-09-24T10:00:00Z");
        SimulatedVehicleState state = null;

        for (int tick = 0; tick < 3000; tick++) {
            recordedAt = recordedAt.plus(PROD_TICK_INTERVAL);
            state = madridGenerator.next(state, random);
            TelemetryMessage message = new TelemetryMessage(
                vehicleId, recordedAt, state.lat(), state.lon(), state.speedKmh(), state.heading(), state.ignition()
            );

            assertThat(filter.isPlausible(message)).isTrue();
        }
    }
}
