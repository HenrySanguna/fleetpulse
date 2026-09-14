package dev.fleetpulse.processor.simulator;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Task 5.3: owns N SimulatedVehicle instances and ticks every one of them
// from a SINGLE shared scheduled background thread. One scheduler instead
// of one thread per vehicle keeps thread count constant as vehicleCount
// grows; each tick only reassigns each vehicle's own state field, and the
// vehicles list itself is built once in connect() and never appended to
// afterward -- nothing here accumulates into a collection that survives
// past one tick. This is the structural reason a 50-vehicle, 10-minute run
// shows flat memory instead of O(vehicles * ticks) growth.
public final class DeviceSimulatorFleet implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulatorFleet.class);

    private final List<SimulatedVehicle> vehicles;
    private final Duration telemetryInterval;
    private final TelemetrySampleGenerator generator = new TelemetrySampleGenerator();
    private final Random random = new Random();
    private ScheduledExecutorService scheduler;

    DeviceSimulatorFleet(List<SimulatedVehicle> vehicles, Duration telemetryInterval) {
        this.vehicles = vehicles;
        this.telemetryInterval = telemetryInterval;
    }

    public static DeviceSimulatorFleet connect(DeviceSimulatorSettings settings) throws MqttException {
        List<SimulatedVehicle> vehicles = new ArrayList<>(settings.vehicleCount());
        for (int i = 0; i < settings.vehicleCount(); i++) {
            vehicles.add(new SimulatedVehicle(
                settings.orgId(), UUID.randomUUID(), settings.brokerUrl(), settings.username(), settings.password()
            ));
        }
        return new DeviceSimulatorFleet(vehicles, settings.telemetryInterval());
    }

    public List<SimulatedVehicle> vehicles() {
        return vehicles;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "device-simulator-tick");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = telemetryInterval.toMillis();
        scheduler.scheduleAtFixedRate(this::tickAll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void tickAll() {
        for (SimulatedVehicle vehicle : vehicles) {
            try {
                vehicle.publishNextTelemetry(generator, random);
            } catch (MqttException ex) {
                log.warn("Failed to publish telemetry for a simulated vehicle", ex);
            }
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void close() {
        stop();
        for (SimulatedVehicle vehicle : vehicles) {
            try {
                vehicle.close();
            } catch (MqttException ex) {
                log.warn("Failed to close a simulated vehicle's MQTT client", ex);
            }
        }
    }
}
