package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import dev.fleetpulse.processor.mqtt.SecuredMosquittoTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Test 6.9: a malformed telemetry payload must be discarded and counted
// without derailing the consumer -- messages published after it must still
// be validated and processed normally. Wires the real production
// TelemetryMqttConfig/TelemetryPayloadParser/TelemetryMessageListener beans
// against a real, secured Mosquitto broker (same convention as
// MqttBrokerHealthIndicatorTest/ProcessorHeartbeatPublisherTest), through a
// minimal AnnotationConfigApplicationContext rather than @SpringBootTest:
// this is the first message-driven channel adapter under test in this
// module, and it needs a real Spring lifecycle (afterPropertiesSet + start)
// that plain `new` + direct method calls do not provide, without pulling in
// the full application context (datasource, JPA, etc.) this test has no use
// for.
@Testcontainers
class TelemetryMqttConsumerTest {

    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    private static final UUID VEHICLE_ID = UUID.randomUUID();
    private static final String ORG_ID = "org-1";

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void malformedPayloadIsDiscardedAndSubsequentValidMessagesAreStillProcessed() throws Exception {
        context = startContext();
        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);

        // Warm-up: the Paho async client connects and subscribes after
        // context refresh completes, so retry a valid publish until it is
        // actually observed instead of guessing a fixed delay. Each attempt
        // publishes once and then only waits (never republishes while a
        // previous attempt might still be in flight), so the counter settles
        // on exactly the number of messages actually delivered.
        warmUpUntilSubscribed(meterRegistry);
        double validCountAfterWarmup = counterValue(meterRegistry, "fleetpulse.telemetry.messages.valid");
        double malformedCountBefore = counterValue(meterRegistry, "fleetpulse.telemetry.messages.malformed");

        publish("not-json-at-all");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(counterValue(meterRegistry, "fleetpulse.telemetry.messages.malformed"))
                .isGreaterThan(malformedCountBefore));
        assertThat(counterValue(meterRegistry, "fleetpulse.telemetry.messages.valid")).isEqualTo(validCountAfterWarmup);

        double malformedCountAfterFirstDiscard = counterValue(meterRegistry, "fleetpulse.telemetry.messages.malformed");

        publish(validPayload(Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(counterValue(meterRegistry, "fleetpulse.telemetry.messages.valid"))
                .isGreaterThan(validCountAfterWarmup));
        assertThat(counterValue(meterRegistry, "fleetpulse.telemetry.messages.malformed")).isEqualTo(malformedCountAfterFirstDiscard);
    }

    private void warmUpUntilSubscribed(MeterRegistry meterRegistry) throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            publish(validPayload(Instant.now()));
            try {
                await().atMost(Duration.ofSeconds(2))
                    .until(() -> counterValue(meterRegistry, "fleetpulse.telemetry.messages.valid") >= 1);
                return;
            } catch (org.awaitility.core.ConditionTimeoutException timedOut) {
                // Subscription likely was not active yet when this attempt
                // published; the message was dropped (QoS 0, no subscriber
                // at delivery time), so retry with a fresh message.
            }
        }
        throw new AssertionError("Telemetry adapter never became ready to receive messages after 10 attempts");
    }

    private static double counterValue(MeterRegistry meterRegistry, String name) {
        return meterRegistry.find(name).counter() == null ? 0.0 : meterRegistry.find(name).counter().count();
    }

    private AnnotationConfigApplicationContext startContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(FleetpulseMqttProperties.class, () -> new FleetpulseMqttProperties(brokerUrl()));
        ctx.registerBean(FleetpulseMqttServiceCredentialsProperties.class, () -> new FleetpulseMqttServiceCredentialsProperties(
            SecuredMosquittoTestSupport.SERVICE_USERNAME, SecuredMosquittoTestSupport.SERVICE_PASSWORD
        ));
        ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        // @EnableIntegration registers the MessagingAnnotationPostProcessor
        // that turns @ServiceActivator into an actual channel subscriber;
        // the real app gets this for free from Boot's IntegrationAutoConfiguration,
        // but this bare AnnotationConfigApplicationContext does not.
        ctx.register(IntegrationTestConfig.class, TelemetryMqttConfig.class, TelemetryPayloadParser.class, TelemetryMessageListener.class);
        ctx.refresh();
        return ctx;
    }

    @EnableIntegration
    @Configuration
    static class IntegrationTestConfig {
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    private static String validPayload(Instant recordedAt) {
        return "{\"recordedAt\":\"" + recordedAt + "\",\"lat\":40.4,\"lon\":-3.7,\"speedKmh\":50.0}";
    }

    private static String topic() {
        return "fleet/" + ORG_ID + "/vehicle/" + VEHICLE_ID + "/telemetry";
    }

    private void publish(String payload) throws Exception {
        MqttClient client = new MqttClient(brokerUrl(), "fleetpulse-test-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        try {
            client.connect(options);
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(0);
            client.publish(topic(), message);
        } finally {
            client.disconnect();
            client.close();
        }
    }
}
