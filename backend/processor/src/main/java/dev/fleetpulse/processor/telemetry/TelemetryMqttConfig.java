package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;
import org.springframework.util.StringUtils;

import java.util.UUID;

// Tasks 2.1/2.2: the telemetry inbound adapter, QoS 0. design.md's QoS table
// also differentiates command (QoS 1) and alert (QoS 2) topics, but no task
// in this change defines a command topic anywhere, and the alerts topic
// belongs to 06-add-trips-eta-alerts ("todas las alertas ... llegan a la
// consola por el mismo tópico MQTT con QoS 2"). Wiring command/alert
// adapters here would invent scope this change does not own; only the
// telemetry adapter is configured.
@Configuration
public class TelemetryMqttConfig {

    static final String TELEMETRY_TOPIC_FILTER = "fleet/+/vehicle/+/telemetry";
    static final int TELEMETRY_QOS = 0;
    static final String TELEMETRY_INPUT_CHANNEL = "telemetryInputChannel";

    @Bean(name = TELEMETRY_INPUT_CHANNEL)
    public MessageChannel telemetryInputChannel() {
        return new DirectChannel();
    }

    // Distinct from the shared MqttClientFactoryConfig bean used by
    // MqttBrokerHealthIndicator/ProcessorHeartbeatPublisher: that one is
    // tuned for short-lived, fail-fast connections. This adapter holds one
    // long-lived subscription and must reconnect automatically instead of
    // giving up when the broker connection drops.
    @Bean
    public MqttPahoClientFactory telemetryInboundMqttClientFactory(FleetpulseMqttServiceCredentialsProperties serviceCredentials) {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setAutomaticReconnect(true);
        connectOptions.setCleanSession(true);
        if (StringUtils.hasText(serviceCredentials.username()) && StringUtils.hasText(serviceCredentials.password())) {
            connectOptions.setUserName(serviceCredentials.username());
            connectOptions.setPassword(serviceCredentials.password().toCharArray());
        }
        factory.setConnectionOptions(connectOptions);
        return factory;
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter telemetryInboundAdapter(
        FleetpulseMqttProperties mqttProperties,
        MqttPahoClientFactory telemetryInboundMqttClientFactory,
        MessageChannel telemetryInputChannel
    ) {
        String clientId = "fleetpulse-processor-telemetry-" + UUID.randomUUID();
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
            mqttProperties.brokerUrl(), clientId, telemetryInboundMqttClientFactory, TELEMETRY_TOPIC_FILTER
        );
        adapter.setQos(TELEMETRY_QOS);
        adapter.setOutputChannel(telemetryInputChannel);
        return adapter;
    }
}
