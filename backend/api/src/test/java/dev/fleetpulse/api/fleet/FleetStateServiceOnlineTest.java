package dev.fleetpulse.api.fleet;

import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import dev.fleetpulse.geocore.MotionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FleetStateServiceOnlineTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID ORG_ID = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private FleetStateService serviceWith(VehicleStateRow row, Vehicle vehicle) {
        VehicleRepository repository = mock(VehicleRepository.class);
        List<Vehicle> orgVehicles = List.of(vehicle);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(orgVehicles);
        ObjectProvider<VehicleRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(repository);
        VehicleStateJdbcReader reader = mock(VehicleStateJdbcReader.class);
        Map<UUID, VehicleStateRow> states = row == null ? Map.of() : Map.of(vehicle.getId(), row);
        when(reader.findByVehicleIds(any())).thenReturn(states);
        return new FleetStateService(
            provider, reader, Clock.fixed(NOW, ZoneOffset.UTC),
            new FleetpulseFleetStateProperties(Duration.ofMinutes(5)));
    }

    private static Vehicle vehicle() {
        Vehicle vehicle = mock(Vehicle.class);
        when(vehicle.getId()).thenReturn(UUID.randomUUID());
        when(vehicle.getLabel()).thenReturn("Truck");
        return vehicle;
    }

    private static VehicleStateRow row(Instant recordedAt, boolean online) {
        return new VehicleStateRow(1.0, 2.0, recordedAt, MotionState.MOVING, online, null, null, null, null, null);
    }

    private boolean onlineFor(Instant recordedAt, boolean storedOnline) {
        Vehicle vehicle = vehicle();
        return serviceWith(row(recordedAt, storedOnline), vehicle)
            .currentState(ORG_ID).vehicles().get(0).online();
    }

    @Test
    void staysOnlineWhenStoredOnlineAndLastReportIsRecent() {
        assertThat(onlineFor(NOW.minusSeconds(299), true)).isTrue();
    }

    @Test
    void goesOfflineWhenLastReportIsOlderThanTheThreshold() {
        assertThat(onlineFor(NOW.minusSeconds(301), true)).isFalse();
    }

    @Test
    void goesOfflineExactlyAtTheThresholdBoundary() {
        assertThat(onlineFor(NOW.minusSeconds(300), true)).isFalse();
    }

    @Test
    void staysOfflineWhenStoredOfflineEvenIfRecent() {
        assertThat(onlineFor(NOW.minusSeconds(10), false)).isFalse();
    }

    @Test
    void isOfflineWhenThereIsNoReportTimestamp() {
        assertThat(onlineFor(null, true)).isFalse();
    }
}
