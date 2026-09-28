package dev.fleetpulse.api.reports;

import dev.fleetpulse.api.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import dev.fleetpulse.geocore.PositionSample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// F6 (QA follow-up): pins ActivityReportService's own "is the requested day
// today (UTC)" gate (computeInProgressTrip) around a real UTC midnight via
// the injected Clock, same layering TripSegmenterTest/
// InProgressTripCalculatorTest already use for their own pure logic.
// ActivityReportService itself is not pure (JDBC-backed collaborators), so
// they are mocked here rather than exercised through Testcontainers -- a
// timing-dependent integration test could never reliably land a request on
// either side of a real midnight.
class ActivityReportServiceClockGateTest {

    private static final UUID VEHICLE_ID = UUID.randomUUID();
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final FleetpulseMotionDetectionProperties MOTION_PROPERTIES =
        new FleetpulseMotionDetectionProperties(5.0, 12.0, Duration.ofSeconds(30));

    private VehicleRepository vehicleRepository;
    private JdbcActivityReportRepository repository;
    private InProgressTripJdbcReader inProgressTripReader;

    @BeforeEach
    void setUp() {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn(ORGANIZATION_ID);
        Vehicle vehicle = mock(Vehicle.class);
        when(vehicle.getId()).thenReturn(VEHICLE_ID);
        when(vehicle.getOrganization()).thenReturn(organization);

        vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle));

        repository = mock(JdbcActivityReportRepository.class);
        when(repository.findDaily(any(), any(), any(), any())).thenReturn(List.of());
        when(repository.findTrips(any(), any(), any(), any())).thenReturn(List.of());

        inProgressTripReader = mock(InProgressTripJdbcReader.class);
    }

    @Test
    void aRequestForYesterdayNeverReadsPositionsAndOmitsTheInProgressTrip() {
        // Clock 30s after UTC midnight on 2026-01-02: 2026-01-01 is already yesterday.
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T00:00:30Z"), ZoneOffset.UTC);

        ActivityReportResponse response = newService(clock).report(
            VEHICLE_ID, ORGANIZATION_ID, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T12:00:00Z"));

        assertThat(response.inProgressTrip()).isNull();
        verifyNoInteractions(inProgressTripReader);
    }

    @Test
    void aRequestForTodayReadsPositionsAndIncludesTheInProgressTrip() {
        // Same instant as above, but the requested range now reaches 2026-01-02 itself.
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T00:00:30Z"), ZoneOffset.UTC);
        stubAnOpenTrip();

        ActivityReportResponse response = newService(clock).report(
            VEHICLE_ID, ORGANIZATION_ID, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:20Z"));

        assertThat(response.inProgressTrip()).isNotNull();
    }

    @Test
    void aRequestForTodayOneSecondBeforeMidnightStillIncludesTheInProgressTrip() {
        // The edge on the other side of the boundary: with one second left
        // before UTC midnight, 2026-01-01 is still today.
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T23:59:59Z"), ZoneOffset.UTC);
        stubAnOpenTrip();

        ActivityReportResponse response = newService(clock).report(
            VEHICLE_ID, ORGANIZATION_ID, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T23:59:00Z"));

        assertThat(response.inProgressTrip()).isNotNull();
    }

    // Mirrors InProgressTripCalculatorTest's own
    // aVehicleStillMovingAfterTheLastClosedTripIsInProgress() fixture: four
    // steady 40km/h samples 30s apart, MOVING confirmed at the second one,
    // nothing since has stopped long enough to close it.
    private void stubAnOpenTrip() {
        when(inProgressTripReader.lastClosedTripEndedAt(VEHICLE_ID)).thenReturn(Instant.EPOCH);
        when(inProgressTripReader.stopThreshold(ORGANIZATION_ID)).thenReturn(Duration.ofSeconds(300));
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        List<PositionSample> positions = List.of(
            new PositionSample(base, 4.71, -74.07, 40.0, false),
            new PositionSample(base.plusSeconds(30), 4.71, -74.0699, 40.0, false),
            new PositionSample(base.plusSeconds(60), 4.71, -74.0698, 40.0, false),
            new PositionSample(base.plusSeconds(90), 4.71, -74.0697, 40.0, false)
        );
        when(inProgressTripReader.positionsSince(any(), any(), any())).thenReturn(positions);
    }

    private ActivityReportService newService(Clock clock) {
        return new ActivityReportService(objectProviderOf(vehicleRepository), repository, inProgressTripReader, MOTION_PROPERTIES, clock);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VehicleRepository> objectProviderOf(VehicleRepository vehicleRepository) {
        ObjectProvider<VehicleRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(vehicleRepository);
        return provider;
    }
}
