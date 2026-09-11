package dev.fleetpulse.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

// Proves task 1.1's core relational model (Organization -> User with role)
// against a real PostGIS-based Postgres via Testcontainers, per project.md's
// non-negotiable "Testcontainers reales, nunca mocks" rule.
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class FleetAuthEntityPersistenceTest {

    // Same recipe docker-compose.yml builds for the "postgis" service: keep the
    // Dockerfile as the single source of truth instead of duplicating it here.
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
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
    }

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private VehicleRepository vehicles;

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private MqttCredentialRepository credentials;

    @Test
    void persistsOrganizationScopedDispatcherWithRole() {
        Organization acme = organizations.save(new Organization("Acme Logistics"));

        User dispatcher = users.save(new User(acme, "ana@acme.test", "hashed-pw", UserRole.DISPATCHER));

        User reloaded = users.findById(dispatcher.getId()).orElseThrow();
        assertThat(reloaded.getOrganization().getId()).isEqualTo(acme.getId());
        assertThat(reloaded.getEmail()).isEqualTo("ana@acme.test");
        assertThat(reloaded.getRole()).isEqualTo(UserRole.DISPATCHER);
        assertThat(reloaded.isActive()).isTrue();
    }

    @Test
    void persistsFleetAdminRoleDistinctFromDispatcher() {
        Organization acme = organizations.save(new Organization("Acme Fleet Admin Org"));

        User admin = users.save(new User(acme, "admin@acme.test", "hashed-pw", UserRole.FLEET_ADMIN));

        assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.FLEET_ADMIN);
    }

    // Task 1.2: Device -> Vehicle -> Organization must resolve as a chain,
    // since the ACL a device gets (design.md) is scoped by its vehicle, and
    // a vehicle's org is what fleet/{orgId}/vehicle/{vehicleId}/... encodes.
    @Test
    void resolvesDeviceThroughVehicleToItsOrganization() {
        Organization acme = organizations.save(new Organization("Acme Chain Org"));
        Vehicle truck = vehicles.save(new Vehicle(acme, "Truck-42"));
        Device tracker = devices.save(new Device(truck, "device-serial-001"));

        Device reloaded = devices.findById(tracker.getId()).orElseThrow();
        assertThat(reloaded.getVehicle().getId()).isEqualTo(truck.getId());
        assertThat(reloaded.getVehicle().getOrganization().getId()).isEqualTo(acme.getId());
    }

    @Test
    void persistsDeviceOwnedCredentialWithoutAUserOwner() {
        Organization acme = organizations.save(new Organization("Acme Device Cred Org"));
        Vehicle truck = vehicles.save(new Vehicle(acme, "Truck-77"));
        Device tracker = devices.save(new Device(truck, "device-serial-077"));

        MqttCredential credential = credentials
            .save(MqttCredential.forDevice("device-077", "hashed-secret", tracker));

        MqttCredential reloaded = credentials.findById(credential.getId()).orElseThrow();
        assertThat(reloaded.getDevice().getId()).isEqualTo(tracker.getId());
        assertThat(reloaded.getUser()).isNull();
        assertThat(reloaded.getExpiresAt()).isNull();
    }

    @Test
    void persistsDispatcherSessionCredentialWithExpiryAndNoDeviceOwner() {
        Organization acme = organizations.save(new Organization("Acme Session Cred Org"));
        User dispatcher = users.save(new User(acme, "session-owner@acme.test", "hashed-pw", UserRole.DISPATCHER));
        Instant expiresAt = Instant.now().plusSeconds(300);

        MqttCredential credential = credentials
            .save(MqttCredential.forDispatcherSession("session-abc", "hashed-secret", dispatcher, expiresAt));

        MqttCredential reloaded = credentials.findById(credential.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(dispatcher.getId());
        assertThat(reloaded.getDevice()).isNull();
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
    }

    // Task 4.2/4.3: revocation and rotation both need to find a device's
    // CURRENT active credential without picking up an older, already-revoked
    // one left behind for audit purposes (MqttCredential.revoke() sets
    // revokedAt but never deletes the row).
    @Test
    void findsOnlyTheActiveNonRevokedCredentialForADevice() {
        Organization acme = organizations.save(new Organization("Acme Active Cred Org"));
        Vehicle truck = vehicles.save(new Vehicle(acme, "Truck-99"));
        Device tracker = devices.save(new Device(truck, "device-serial-099"));

        MqttCredential revoked = credentials.save(MqttCredential.forDevice("device-099-old", "hashed-old", tracker));
        revoked.revoke();
        credentials.save(revoked);
        MqttCredential active = credentials.save(MqttCredential.forDevice("device-099-new", "hashed-new", tracker));

        MqttCredential found = credentials.findByDeviceAndRevokedAtIsNull(tracker).orElseThrow();
        assertThat(found.getId()).isEqualTo(active.getId());
    }
}
