package dev.fleetpulse.processor.alerts;

// Port AlertRuleDispatcher publishes through (tasks 3.2/3.3), kept separate
// from the real MQTT implementation (MqttAlertPublisher) for the same reason
// GeofenceAlertPublisher/EtaPublisher are their own interfaces: tests that
// never trigger a speeding/excessive-idle condition can wire
// AlertRuleDispatcher against a no-op implementation instead of standing up
// a broker connection they have no use for.
@FunctionalInterface
public interface AlertPublisher {

    void publish(Alert alert);
}
