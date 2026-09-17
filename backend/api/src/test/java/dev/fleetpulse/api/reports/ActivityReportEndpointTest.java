package dev.fleetpulse.api.reports;

import dev.fleetpulse.api.security.DispatcherLoginTestSupport;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.OrganizationRepository;
import dev.fleetpulse.domain.User;
import dev.fleetpulse.domain.UserRepository;
import dev.fleetpulse.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

// Task 4.3: GET /api/vehicles/{vehicleId}/activity-report?from=&to= -- real
// Testcontainers PostGIS, same recipe as AlertsEndpointTest/
// VehicleDestinationEndpointTest. Neither vehicle_daily (V13, WU5) nor trips
// (V10, WU1) has an API-level write path of its own (only the processor
// module writes them), so every fixture here seeds directly via raw SQL,
// the same shape AlertsEndpointTest.seedAlert() already established for a
// table this module only reads.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ActivityReportEndpointTest {

    private static final Path POSTGIS_PARTMAN_DOCKERFILE = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "postgis-partman", "Dockerfile")
        .normalize();

    @Container
    static final PostgreSQLContainer postgis = new PostgreSQLContainer(
        DockerImageName
            .parse(
                new ImageFromDockerfile("fleetpulse/postgis-partman:test", false)
                    .withDockerfile(POSTGIS_PARTMAN_DOCKERFILE)
                    .get()
            )
            .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        registry.add("fleetpulse.mqtt.broker-url", () -> "tcp://localhost:1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aggregatesTheSummaryAndDailyDistancesFromVehicleDailyAndListsTripsNewestFirst() throws SQLException {
        Organization org = organizations.save(new Organization("acme-activity-report"));
        users.save(new User(org, "activity-report@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Activity-1");
        LocalDate today = LocalDate.now();
        seedDailyRow(org.getId(), vehicleId, today.minusDays(1), 60.0f, 3600, 600, 80.0f);
        seedDailyRow(org.getId(), vehicleId, today, 40.0f, 1800, 300, 70.0f);
        Instant olderStart = Instant.now().minus(20, ChronoUnit.HOURS);
        Instant newerStart = Instant.now().minus(2, ChronoUnit.HOURS);
        seedTrip(UUID.randomUUID(), org.getId(), vehicleId, olderStart, olderStart.plus(1, ChronoUnit.HOURS), 30.0f, 3600, 300, 75.0f, 30.0f);
        seedTrip(UUID.randomUUID(), org.getId(), vehicleId, newerStart, newerStart.plus(1, ChronoUnit.HOURS), 25.0f, 3300, 200, 68.0f, 27.0f);

        ResponseEntity<ActivityReportResponse> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/activity-report?from=" + today.minusDays(6) + "T00:00:00Z&to=" + today + "T23:59:59Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-report@acme.test")), ActivityReportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ActivityReportResponse body = response.getBody();
        assertThat(body.vehicleId()).isEqualTo(vehicleId);
        assertThat(body.summary().totalDistanceKm()).isCloseTo(100.0, offset(0.01));
        assertThat(body.summary().movingMinutes()).isEqualTo(90);
        assertThat(body.summary().idleMinutes()).isEqualTo(15);
        assertThat(body.summary().maxSpeedKmh()).isEqualTo(80.0);
        assertThat(body.dailyDistances()).hasSize(2);
        assertThat(body.dailyDistances().get(0).day()).isEqualTo(today.minusDays(1));
        assertThat(body.dailyDistances().get(1).day()).isEqualTo(today);
        assertThat(body.trips()).hasSize(2);
        // Truncated to milliseconds on both sides: the round trip through
        // java.sql.Timestamp/PostgreSQL's own microsecond storage can round
        // (not just truncate) the very last microsecond digit, which
        // millisecond-level comparison safely ignores without weakening the
        // real assertion (this test only cares that the right trip sorted
        // first, not sub-millisecond timestamp fidelity).
        assertThat(body.trips().get(0).startedAt().truncatedTo(ChronoUnit.MILLIS))
            .isEqualTo(newerStart.truncatedTo(ChronoUnit.MILLIS));
        assertThat(body.trips().get(1).startedAt().truncatedTo(ChronoUnit.MILLIS))
            .isEqualTo(olderStart.truncatedTo(ChronoUnit.MILLIS));
        assertThat(body.trips().get(0).durationMinutes()).isEqualTo(55);
    }

    @Test
    void excludesDailyRowsAndTripsOutsideTheRequestedRange() throws SQLException {
        Organization org = organizations.save(new Organization("acme-activity-report-range"));
        users.save(new User(org, "activity-range@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Activity-Range");
        LocalDate today = LocalDate.now();
        seedDailyRow(org.getId(), vehicleId, today, 40.0f, 1800, 300, 70.0f);
        seedDailyRow(org.getId(), vehicleId, today.minusDays(30), 999.0f, 3600, 0, 120.0f);
        Instant inRange = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant outOfRange = Instant.now().minus(60, ChronoUnit.DAYS);
        seedTrip(UUID.randomUUID(), org.getId(), vehicleId, inRange, inRange.plus(1, ChronoUnit.HOURS), 25.0f, 3300, 200, 68.0f, 27.0f);
        seedTrip(UUID.randomUUID(), org.getId(), vehicleId, outOfRange, outOfRange.plus(1, ChronoUnit.HOURS), 999.0f, 3600, 0, 200.0f, 999.0f);

        ResponseEntity<ActivityReportResponse> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/activity-report?from=" + today.minusDays(6) + "T00:00:00Z&to=" + today + "T23:59:59Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-range@acme.test")), ActivityReportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().dailyDistances()).hasSize(1);
        assertThat(response.getBody().summary().totalDistanceKm()).isCloseTo(40.0, offset(0.01));
        assertThat(response.getBody().trips()).hasSize(1);
    }

    @Test
    void returnsAnEmptyReportForAVehicleWithNoRolledUpDataYet() throws SQLException {
        Organization org = organizations.save(new Organization("acme-activity-report-empty"));
        users.save(new User(org, "activity-empty@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Activity-Empty");
        LocalDate today = LocalDate.now();

        ResponseEntity<ActivityReportResponse> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/activity-report?from=" + today.minusDays(6) + "T00:00:00Z&to=" + today + "T23:59:59Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-empty@acme.test")), ActivityReportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ActivityReportResponse body = response.getBody();
        assertThat(body.summary().totalDistanceKm()).isEqualTo(0.0);
        assertThat(body.summary().avgSpeedKmh()).isEqualTo(0.0);
        assertThat(body.summary().maxSpeedKmh()).isNull();
        assertThat(body.dailyDistances()).isEmpty();
        assertThat(body.trips()).isEmpty();
    }

    @Test
    void hidesAnotherOrganizationsVehicleAs404() throws SQLException {
        Organization orgA = organizations.save(new Organization("acme-activity-report-iso-a"));
        Organization orgB = organizations.save(new Organization("acme-activity-report-iso-b"));
        users.save(new User(orgA, "activity-iso-a@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleInOrgB = seedVehicle(orgB.getId(), "Truck-Activity-Other-Org");
        LocalDate today = LocalDate.now();

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleInOrgB + "/activity-report?from=" + today.minusDays(6) + "T00:00:00Z&to=" + today + "T23:59:59Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-iso-a@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsAMalformedFromParameterAsBadRequest() throws SQLException {
        Organization org = organizations.save(new Organization("acme-activity-report-bad-from"));
        users.save(new User(org, "activity-bad-from@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Activity-Bad-From");

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/activity-report?from=not-a-date&to=2026-09-17T00:00:00Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-bad-from@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Unlike VehicleTrackController/AlertsController (both meaningful with
    // no from/to filter at all), from/to are @RequestParam WITHOUT
    // `required = false` here -- Spring's own default MissingServletRequestParameterException
    // handling already returns 400, proven directly rather than assumed.
    @Test
    void rejectsAMissingToParameterAsBadRequest() throws SQLException {
        Organization org = organizations.save(new Organization("acme-activity-report-missing-to"));
        users.save(new User(org, "activity-missing-to@acme.test", passwordEncoder.encode("s3cret-pass"), UserRole.DISPATCHER));
        UUID vehicleId = seedVehicle(org.getId(), "Truck-Activity-Missing-To");

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/vehicles/" + vehicleId + "/activity-report?from=2026-09-10T00:00:00Z",
            HttpMethod.GET, new HttpEntity<>(sessionHeaders("activity-missing-to@acme.test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private org.springframework.http.HttpHeaders sessionHeaders(String email) {
        return DispatcherLoginTestSupport.sessionHeadersFrom(
            DispatcherLoginTestSupport.login(restTemplate, baseUrl(), email, "s3cret-pass"));
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static UUID seedVehicle(UUID organizationId, String label) throws SQLException {
        UUID vehicleId = UUID.randomUUID();
        try (
            Connection connection = connect();
            PreparedStatement statement = connection
                .prepareStatement("INSERT INTO vehicles (id, organization_id, label, created_at) VALUES (?, ?, ?, ?)")
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setString(3, label);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        return vehicleId;
    }

    private static void seedDailyRow(
            UUID organizationId, UUID vehicleId, LocalDate day, float distanceKm, int movingSecs, int idleSecs, float maxSpeedKmh)
            throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicle_daily (vehicle_id, organization_id, day, distance_km, moving_secs, idle_secs, max_speed_kmh, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        ) {
            statement.setObject(1, vehicleId);
            statement.setObject(2, organizationId);
            statement.setDate(3, Date.valueOf(day));
            statement.setFloat(4, distanceKm);
            statement.setInt(5, movingSecs);
            statement.setInt(6, idleSecs);
            statement.setFloat(7, maxSpeedKmh);
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void seedTrip(
            UUID id, UUID organizationId, UUID vehicleId, Instant startedAt, Instant endedAt,
            float distanceKm, int durationSecs, int idleSecs, float maxSpeedKmh, float avgSpeedKmh)
            throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO trips (id, organization_id, vehicle_id, started_at, ended_at, distance_km, duration_secs, idle_secs, "
                    + "max_speed_kmh, avg_speed_kmh, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        ) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setObject(3, vehicleId);
            statement.setTimestamp(4, Timestamp.from(startedAt));
            statement.setTimestamp(5, Timestamp.from(endedAt));
            statement.setFloat(6, distanceKm);
            statement.setInt(7, durationSecs);
            statement.setInt(8, idleSecs);
            statement.setFloat(9, maxSpeedKmh);
            statement.setFloat(10, avgSpeedKmh);
            statement.setTimestamp(11, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
    }
}
