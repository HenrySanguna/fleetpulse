package dev.fleetpulse.api.mqtt;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;

// 02-add-fleet-auth (tasks 5.1/5.2): builds a fresh, independent
// eclipse-mosquitto:2 container bootstrapped with the same
// docker/mosquitto/mosquitto.conf + bootstrap-and-run.sh used by real
// docker-compose deployments, secured with dynamic-security and anonymous
// access denied on both listeners. Returns a NEW GenericContainer instance
// per call -- callers still declare their OWN `@Container static final`
// field (never a shared cross-class static container), matching this
// module's existing per-test-class Testcontainers convention (see
// DispatcherSessionAuthenticationTest and friends). Public: reused from
// dev.fleetpulse.api.mqtt.credentials' spec-scenario tests too.
public final class SecuredMosquittoTestSupport {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "test-dynsec-admin";
    public static final String SERVICE_USERNAME = "internal-services";
    public static final String SERVICE_PASSWORD = "test-dynsec-service";

    private static final Path MOSQUITTO_CONF = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "mosquitto", "mosquitto.conf")
        .normalize();

    private static final Path BOOTSTRAP_SCRIPT = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "mosquitto", "bootstrap-and-run.sh")
        .normalize();

    private SecuredMosquittoTestSupport() {
    }

    public static GenericContainer<?> newContainer() {
        return new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
            .withCopyFileToContainer(MountableFile.forHostPath(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
            .withCopyFileToContainer(MountableFile.forHostPath(BOOTSTRAP_SCRIPT), "/mosquitto/bootstrap-and-run.sh")
            .withEnv("MOSQUITTO_DYNSEC_ADMIN_USERNAME", ADMIN_USERNAME)
            .withEnv("MOSQUITTO_DYNSEC_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("MOSQUITTO_DYNSEC_SERVICE_USERNAME", SERVICE_USERNAME)
            .withEnv("MOSQUITTO_DYNSEC_SERVICE_PASSWORD", SERVICE_PASSWORD)
            .withCommand("sh", "/mosquitto/bootstrap-and-run.sh")
            .withExposedPorts(1883, 9001)
            // The broker's listen socket opens before bootstrap-and-run.sh
            // finishes provisioning the "internal-services" identity (that
            // happens afterward, over the broker's own control topic), so a
            // plain Wait.forListeningPort() alone would be a race: wait for
            // its last provisioning step's log line instead.
            .waitingFor(Wait.forLogMessage(".*addClientRole.*\\n", 1).withStartupTimeout(Duration.ofSeconds(60)));
    }
}
