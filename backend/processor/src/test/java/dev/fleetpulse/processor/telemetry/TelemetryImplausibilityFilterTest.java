package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 2.4: Geo.isImplausible (geo-core) needs a "last known position" per
// vehicle to compare against. This filter keeps its own in-memory
// last-accepted-position-per-vehicle map instead of reading vehicle_state:
// nothing in this work unit writes vehicle_state yet (that guard is WU6's),
// so vehicle_state.location would stay NULL for every vehicle and this
// filter could never reject anything. The reference only ever advances
// monotonically by recordedAt, so a late resend never resets it backward --
// a concern this filter owns independently of, and without duplicating,
// WU6's future `UPDATE vehicle_state ... WHERE recorded_at < ?` guard.
class TelemetryImplausibilityFilterTest {

    private static final UUID VEHICLE_ID = UUID.randomUUID();

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TelemetryImplausibilityFilter filter = new TelemetryImplausibilityFilter(
        new FleetpulseTelemetryImplausibilityProperties(300.0), meterRegistry
    );

    @Test
    void firstPositionForAVehicleIsAlwaysPlausible() {
        TelemetryMessage message = telemetryAt(Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7);

        assertThat(filter.isPlausible(message)).isTrue();
    }

    @Test
    void discardsAPositionThatImpliesAPhysicallyImpossibleSpeed() {
        filter.isPlausible(telemetryAt(Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7));

        // ~510 km away, one second later: implausible for any road vehicle.
        TelemetryMessage impossibleJump = telemetryAt(Instant.parse("2026-09-11T10:00:01Z"), 45.0, -3.7);

        assertThat(filter.isPlausible(impossibleJump)).isFalse();
        assertThat(meterRegistry.find("fleetpulse.telemetry.messages.implausible").counter().count()).isEqualTo(1.0);
    }

    @Test
    void acceptsAPositionConsistentWithAnOrdinaryDrivingSpeed() {
        filter.isPlausible(telemetryAt(Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7));

        // ~33 m, ten seconds later: an ordinary driving speed.
        TelemetryMessage plausibleMove = telemetryAt(Instant.parse("2026-09-11T10:00:10Z"), 40.4003, -3.7);

        assertThat(filter.isPlausible(plausibleMove)).isTrue();
    }

    @Test
    void anImplausiblePositionDoesNotBecomeTheNewReference() {
        filter.isPlausible(telemetryAt(Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7));
        filter.isPlausible(telemetryAt(Instant.parse("2026-09-11T10:00:01Z"), 45.0, -3.7));

        // Compared against the original 40.4,-3.7 reference again (not the
        // rejected 45.0,-3.7 jump), so this ordinary move is still plausible.
        TelemetryMessage plausibleMove = telemetryAt(Instant.parse("2026-09-11T10:00:11Z"), 40.4003, -3.7);

        assertThat(filter.isPlausible(plausibleMove)).isTrue();
    }

    @Test
    void aLateResentOldPositionIsNeverFlaggedImplausible() {
        filter.isPlausible(telemetryAt(Instant.parse("2026-09-11T10:00:00Z"), 40.4, -3.7));

        // A resend from hours earlier and far away: Geo.isImplausible only
        // compares speed forward in time, so an out-of-order message is
        // never rejected by this filter -- design.md's disorder tolerance
        // for vehicle_state itself belongs to WU6, not here.
        TelemetryMessage oldResend = telemetryAt(Instant.parse("2026-09-11T06:00:00Z"), -10.0, 100.0);

        assertThat(filter.isPlausible(oldResend)).isTrue();
    }

    private static TelemetryMessage telemetryAt(Instant recordedAt, double lat, double lon) {
        return new TelemetryMessage(VEHICLE_ID, recordedAt, lat, lon, null, null, null);
    }
}
