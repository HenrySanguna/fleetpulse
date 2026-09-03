package dev.fleetpulse.api.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;

@Configuration
public class MqttClientFactoryConfig {

    // Health checks and the on-demand heartbeat read (5.3) are short-lived
    // connections that must fail fast rather than hang on Paho's 30s default.
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;

    @Bean
    MqttPahoClientFactory mqttPahoClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
        connectOptions.setAutomaticReconnect(false);
        connectOptions.setCleanSession(true);
        factory.setConnectionOptions(connectOptions);
        return factory;
    }
}
