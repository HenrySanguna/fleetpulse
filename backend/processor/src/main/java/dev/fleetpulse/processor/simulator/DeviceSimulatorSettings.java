package dev.fleetpulse.processor.simulator;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

// Task 5.3: configuration surface for the dev-only device simulator. Reads
// from a plain environment-variable map, not Spring @ConfigurationProperties
// -- this tool intentionally never boots a Spring context (DeviceSimulatorMain)
// -- with defaults that let `./gradlew :processor:runDeviceSimulator` work
// with zero configuration against a local docker-compose broker.
public record DeviceSimulatorSettings(
    int vehicleCount,
    String orgId,
    String brokerUrl,
    String username,
    String password,
    Duration telemetryInterval,
    Duration runDuration
) {

    static final int DEFAULT_VEHICLE_COUNT = 50;
    static final String DEFAULT_ORG_ID = "org-1";
    static final String DEFAULT_BROKER_URL = "tcp://localhost:1883";
    static final Duration DEFAULT_TELEMETRY_INTERVAL = Duration.ofSeconds(5);

    public static DeviceSimulatorSettings fromEnvironment(Map<String, String> env) {
        int vehicleCount = intOrDefault(env.get("SIMULATOR_VEHICLE_COUNT"), DEFAULT_VEHICLE_COUNT);
        String orgId = stringOrDefault(env.get("SIMULATOR_ORG_ID"), DEFAULT_ORG_ID);
        String brokerUrl = stringOrDefault(env.get("SIMULATOR_BROKER_URL"), DEFAULT_BROKER_URL);
        String username = env.get("SIMULATOR_MQTT_USERNAME");
        String password = env.get("SIMULATOR_MQTT_PASSWORD");
        Duration telemetryInterval = durationMillisOrDefault(env.get("SIMULATOR_TELEMETRY_INTERVAL_MS"), DEFAULT_TELEMETRY_INTERVAL);
        Duration runDuration = durationMillisOrDefault(env.get("SIMULATOR_RUN_DURATION_MS"), null);
        return new DeviceSimulatorSettings(vehicleCount, orgId, brokerUrl, username, password, telemetryInterval, runDuration);
    }

    public Optional<Duration> boundedRunDuration() {
        return Optional.ofNullable(runDuration);
    }

    private static int intOrDefault(String raw, int defaultValue) {
        return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw);
    }

    private static String stringOrDefault(String raw, String defaultValue) {
        return raw == null || raw.isBlank() ? defaultValue : raw;
    }

    private static Duration durationMillisOrDefault(String rawMillis, Duration defaultValue) {
        return rawMillis == null || rawMillis.isBlank() ? defaultValue : Duration.ofMillis(Long.parseLong(rawMillis));
    }
}
