package dev.fleetpulse.api.health;

import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import dev.fleetpulse.api.mqtt.SecuredMosquittoTestSupport;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MqttBrokerHealthIndicatorTest {

    // 02-add-fleet-auth (tasks 5.1/5.2): the real mosquitto.conf now denies
    // anonymous connections, so this test's broker must be bootstrapped the
    // same way docker-compose.yml's mosquitto service is.
    @Container
    static final GenericContainer<?> mosquitto = SecuredMosquittoTestSupport.newContainer();

    @Test
    void reportsUpWhenBrokerIsReachable() {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        MqttBrokerHealthIndicator indicator = new MqttBrokerHealthIndicator(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl)
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenBrokerIsUnreachable() {
        int unreachablePort = mosquitto.getMappedPort(1883) + 1;
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + unreachablePort;
        MqttBrokerHealthIndicator indicator = new MqttBrokerHealthIndicator(
            testClientFactory(),
            new FleetpulseMqttProperties(brokerUrl)
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static MqttPahoClientFactory testClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3);
        options.setAutomaticReconnect(false);
        // Task 5.1/5.2: the broker denies anonymous connections now, so even
        // this connect-only health check must authenticate as the
        // broad-access "internal-services" identity (the dynsec admin
        // identity cannot be reused here -- see MosquittoDynamicSecurityAdminClient).
        options.setUserName(SecuredMosquittoTestSupport.SERVICE_USERNAME);
        options.setPassword(SecuredMosquittoTestSupport.SERVICE_PASSWORD.toCharArray());
        factory.setConnectionOptions(options);
        return factory;
    }
}
