package dev.fleetpulse.api.mqtt;

import dev.fleetpulse.api.config.FleetpulseMqttServiceCredentialsProperties;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.util.StringUtils;

@Configuration
public class MqttClientFactoryConfig {

    // Health checks and the on-demand heartbeat read (5.3) are short-lived
    // connections that must fail fast rather than hang on Paho's 30s default.
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;

    @Bean
    MqttPahoClientFactory mqttPahoClientFactory(FleetpulseMqttServiceCredentialsProperties serviceCredentials) {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
        connectOptions.setAutomaticReconnect(false);
        connectOptions.setCleanSession(true);
        // 02-add-fleet-auth, tasks 5.1/5.2: the broker denies anonymous
        // connections, so this shared factory (used by
        // MqttBrokerHealthIndicator and ProcessorHeartbeatHealthIndicator)
        // must authenticate as the broad-access "internal-services" dynsec
        // identity. Left unset when blank so contexts that never bind a real
        // value (most existing tests) keep working exactly as before.
        if (StringUtils.hasText(serviceCredentials.username()) && StringUtils.hasText(serviceCredentials.password())) {
            connectOptions.setUserName(serviceCredentials.username());
            connectOptions.setPassword(serviceCredentials.password().toCharArray());
        }
        factory.setConnectionOptions(connectOptions);
        return factory;
    }
}
