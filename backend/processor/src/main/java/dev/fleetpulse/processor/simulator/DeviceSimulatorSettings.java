package dev.fleetpulse.processor.simulator;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Task 5.3: configuration surface for the dev-only device simulator. Reads
// from a plain environment-variable map, not Spring @ConfigurationProperties
// -- this tool intentionally never boots a Spring context (DeviceSimulatorMain)
// -- with defaults that let `./gradlew :processor:runDeviceSimulator` work
// with zero configuration against a local docker-compose broker.
public record DeviceSimulatorSettings(
    int vehicleCount,
    List<UUID> vehicleIds,
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
        List<UUID> vehicleIds = parseVehicleIds(env.get("SIMULATOR_VEHICLE_IDS"));
        int vehicleCount = resolveVehicleCount(env.get("SIMULATOR_VEHICLE_COUNT"), vehicleIds);
        String orgId = stringOrDefault(env.get("SIMULATOR_ORG_ID"), DEFAULT_ORG_ID);
        String brokerUrl = stringOrDefault(env.get("SIMULATOR_BROKER_URL"), DEFAULT_BROKER_URL);
        String username = env.get("SIMULATOR_MQTT_USERNAME");
        String password = env.get("SIMULATOR_MQTT_PASSWORD");
        Duration telemetryInterval = durationMillisOrDefault(env.get("SIMULATOR_TELEMETRY_INTERVAL_MS"), DEFAULT_TELEMETRY_INTERVAL);
        Duration runDuration = durationMillisOrDefault(env.get("SIMULATOR_RUN_DURATION_MS"), null);
        return new DeviceSimulatorSettings(vehicleCount, vehicleIds, orgId, brokerUrl, username, password, telemetryInterval, runDuration);
    }

    public Optional<Duration> boundedRunDuration() {
        return Optional.ofNullable(runDuration);
    }

    // Prod demo (task 5.1's decision): fixed vehicle IDs are required so
    // random UUID.randomUUID() (DeviceSimulatorFleet.connect) never violates
    // the positions/vehicle_state FK to vehicles(id) -- those rows only
    // exist for the seeded demo fleet, not for whatever UUID a fresh
    // process run would otherwise pick. Local dev leaves this unset and
    // keeps the random-UUID behaviour untouched.
    private static List<UUID> parseVehicleIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .map(part -> {
                try {
                    return UUID.fromString(part);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                        "SIMULATOR_VEHICLE_IDS contains a malformed UUID: '" + part + "'", ex
                    );
                }
            })
            .toList();
    }

    // Keeping this simple on purpose: when SIMULATOR_VEHICLE_IDS is set, it
    // is the single source of truth for the fleet size -- SIMULATOR_VEHICLE_COUNT
    // is only allowed alongside it when the two already agree, so a stale
    // leftover count env var fails loudly instead of silently disconnecting
    // count from the actual ID list.
    private static int resolveVehicleCount(String rawCount, List<UUID> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return intOrDefault(rawCount, DEFAULT_VEHICLE_COUNT);
        }
        if (rawCount != null && !rawCount.isBlank()) {
            int explicitCount = Integer.parseInt(rawCount);
            if (explicitCount != vehicleIds.size()) {
                throw new IllegalArgumentException(
                    "SIMULATOR_VEHICLE_COUNT (" + explicitCount + ") does not match the number of "
                        + "SIMULATOR_VEHICLE_IDS entries (" + vehicleIds.size() + ")"
                );
            }
        }
        return vehicleIds.size();
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
