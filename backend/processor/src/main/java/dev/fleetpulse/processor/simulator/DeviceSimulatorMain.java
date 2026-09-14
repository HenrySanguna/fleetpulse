package dev.fleetpulse.processor.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

// Task 5.3 entry point. Deliberately a plain class with its own main() --
// NOT @SpringBootApplication, NOT component-scanned by ProcessorApplication
// -- so this dev-only tool never becomes part of the production processor
// runtime path (openspec/project.md: "dos procesos desplegables, no tres").
// Run it via the `runDeviceSimulator` Gradle task (processor/build.gradle.kts),
// which points a JavaExec task at this class instead of ProcessorApplication.
// Configure with SIMULATOR_* environment variables -- see
// DeviceSimulatorSettings.fromEnvironment().
//
// Intentionally has NO shutdown hook that publishes an offline announcement
// on exit: the whole point of task 5.1's Last Will contract is that an
// ABRUPT kill (SIGKILL, a crashed device, a lost connection) still results
// in the broker marking the vehicle offline with zero cooperation from the
// dying process. Reimplementing that as a graceful-shutdown code path here
// would duplicate what the broker already guarantees and would stop
// proving the property this tool exists to exercise.
public final class DeviceSimulatorMain {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulatorMain.class);

    private DeviceSimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        DeviceSimulatorSettings settings = DeviceSimulatorSettings.fromEnvironment(System.getenv());
        log.info(
            "Starting device simulator: {} vehicles, org={}, broker={}, telemetryInterval={}",
            settings.vehicleCount(), settings.orgId(), settings.brokerUrl(), settings.telemetryInterval()
        );
        try (DeviceSimulatorFleet fleet = DeviceSimulatorFleet.connect(settings)) {
            fleet.start();
            Duration runDuration = settings.runDuration();
            if (runDuration != null) {
                log.info("Running for {} then stopping cleanly", runDuration);
                Thread.sleep(runDuration.toMillis());
            } else {
                log.info("Running until the process is stopped (Ctrl+C, or a hard kill to exercise the Last Will)");
                new CountDownLatch(1).await();
            }
        }
        log.info("Device simulator stopped");
    }
}
