package dev.fleetpulse.processor.simulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 5.3: pure environment-variable parsing for the dev-only simulator's
// configuration. No Spring @ConfigurationProperties -- DeviceSimulatorMain
// never boots a Spring context (see its own Javadoc) -- so this is tested
// directly against an injected Map instead of System.getenv().
class DeviceSimulatorSettingsTest {

    @Test
    void usesDefaultsWhenNoEnvironmentVariablesAreSet() {
        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(Map.of());

        assertThat(settings.vehicleCount()).isEqualTo(50);
        assertThat(settings.vehicleIds()).isEmpty();
        assertThat(settings.orgId()).isEqualTo("org-1");
        assertThat(settings.brokerUrl()).isEqualTo("tcp://localhost:1883");
        assertThat(settings.telemetryInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.runDuration()).isNull();
    }

    // Triangulation: every override actually overrides its own default and
    // nothing else -- proves each key is read independently, not just that
    // *a* default happens to satisfy the test above.
    @Test
    void everyEnvironmentVariableOverridesItsOwnDefault() {
        Map<String, String> env = Map.of(
            "SIMULATOR_VEHICLE_COUNT", "5",
            "SIMULATOR_ORG_ID", "org-9",
            "SIMULATOR_BROKER_URL", "tcp://broker:1884",
            "SIMULATOR_MQTT_USERNAME", "device-user",
            "SIMULATOR_MQTT_PASSWORD", "device-pass",
            "SIMULATOR_TELEMETRY_INTERVAL_MS", "250",
            "SIMULATOR_RUN_DURATION_MS", "90000"
        );

        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(env);

        assertThat(settings.vehicleCount()).isEqualTo(5);
        assertThat(settings.orgId()).isEqualTo("org-9");
        assertThat(settings.brokerUrl()).isEqualTo("tcp://broker:1884");
        assertThat(settings.username()).isEqualTo("device-user");
        assertThat(settings.password()).isEqualTo("device-pass");
        assertThat(settings.telemetryInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(settings.runDuration()).isEqualTo(Duration.ofMillis(90000));
    }

    @Test
    void runDurationStaysNullWhenUnboundedRunIsRequested() {
        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(Map.of("SIMULATOR_VEHICLE_COUNT", "10"));

        assertThat(settings.boundedRunDuration()).isEmpty();
    }

    // Prod demo (task 5.1): fixed IDs derive the fleet size on their own,
    // no SIMULATOR_VEHICLE_COUNT needed alongside them.
    @Test
    void fixedVehicleIdsDeriveVehicleCountWhenNoCountIsGiven() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<String, String> env = Map.of("SIMULATOR_VEHICLE_IDS", first + ", " + second);

        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(env);

        assertThat(settings.vehicleCount()).isEqualTo(2);
        assertThat(settings.vehicleIds()).containsExactly(first, second);
    }

    @Test
    void fixedVehicleIdsAcceptAMatchingExplicitVehicleCount() {
        UUID onlyId = UUID.randomUUID();
        Map<String, String> env = Map.of(
            "SIMULATOR_VEHICLE_IDS", onlyId.toString(),
            "SIMULATOR_VEHICLE_COUNT", "1"
        );

        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(env);

        assertThat(settings.vehicleCount()).isEqualTo(1);
        assertThat(settings.vehicleIds()).isEqualTo(List.of(onlyId));
    }

    @Test
    void fixedVehicleIdsRejectAMismatchingExplicitVehicleCount() {
        Map<String, String> env = Map.of(
            "SIMULATOR_VEHICLE_IDS", UUID.randomUUID().toString(),
            "SIMULATOR_VEHICLE_COUNT", "5"
        );

        assertThatThrownBy(() -> DeviceSimulatorSettings.fromEnvironment(env))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SIMULATOR_VEHICLE_COUNT")
            .hasMessageContaining("SIMULATOR_VEHICLE_IDS");
    }

    @Test
    void malformedVehicleIdIsRejectedWithAClearError() {
        Map<String, String> env = Map.of("SIMULATOR_VEHICLE_IDS", "not-a-uuid");

        assertThatThrownBy(() -> DeviceSimulatorSettings.fromEnvironment(env))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SIMULATOR_VEHICLE_IDS")
            .hasMessageContaining("not-a-uuid");
    }
}
