package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.processor.config.FleetpulseMqttProperties;
import dev.fleetpulse.processor.config.FleetpulseMqttServiceCredentialsProperties;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.util.StringUtils;

import java.util.UUID;

// Task 4.2: fleet/{orgId}/alerts at QoS 2 -- design.md is explicit this is
// the one topic in the whole system that pays the exactly-once delivery
// cost, deliberately contrasted with telemetry's QoS 0.
//
// No new ACL registration is needed for this topic (confirmed by reading
// 02-add-fleet-auth's dynsec bootstrap and credential services, not assumed):
// docker/mosquitto/bootstrap-and-run.sh already grants the broad-access
// "internal-services" identity (which MqttClientFactoryConfig's shared
// factory -- and this class's own factory below -- authenticate as via
// FleetpulseMqttServiceCredentialsProperties) a wildcard publishClientSend
// '#' ACL, and BrowserMqttCredentialService already grants every dispatcher
// session a subscribePattern fleet/{orgId}/# ACL (task 3.2, 02-add-fleet-auth).
// Both wildcards already cover fleet/{orgId}/alerts without a single ACL
// line changing; DeviceCredentialService's narrower per-vehicle ACLs
// (telemetry/status/command topics only) are correctly left untouched,
// since devices have no business publishing or subscribing to alerts.
//
// A long-lived outbound connection (Spring Integration's
// MqttPahoMessageHandler, the outbound counterpart to
// TelemetryMqttConfig/PresenceMqttConfig's inbound
// MqttPahoMessageDrivenChannelAdapter), not the short-lived
// connect-publish-disconnect round trip ProcessorHeartbeatPublisher/
// MosquittoDynamicSecurityAdminClient use for their own infrequent, one-off
// publishes: alerts fire on ordinary telemetry traffic, not once every 30s,
// so paying a fresh TCP+MQTT handshake per alert would be both slower and
// needlessly hammer the broker's connection churn. No Spring Integration
// outbound adapter existed anywhere in this module before this change --
// this is the first one.
@Configuration
public class AlertMqttConfig {

    static final String ALERT_OUTPUT_CHANNEL = "geofenceAlertOutputChannel";
    static final int ALERT_QOS = 2;

    @Bean(name = ALERT_OUTPUT_CHANNEL)
    public MessageChannel geofenceAlertOutputChannel() {
        return new DirectChannel();
    }

    // Same long-lived, auto-reconnecting shape as TelemetryMqttConfig/
    // PresenceMqttConfig's own inbound factories: this adapter also holds
    // one persistent connection, unlike MqttClientFactoryConfig's
    // short-lived, fail-fast one.
    @Bean
    public MqttPahoClientFactory alertOutboundMqttClientFactory(FleetpulseMqttServiceCredentialsProperties serviceCredentials) {
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
    @ServiceActivator(inputChannel = ALERT_OUTPUT_CHANNEL)
    public MessageHandler alertOutboundMqttHandler(
        FleetpulseMqttProperties mqttProperties,
        MqttPahoClientFactory alertOutboundMqttClientFactory
    ) {
        String clientId = "fleetpulse-processor-alerts-" + UUID.randomUUID();
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(mqttProperties.brokerUrl(), clientId, alertOutboundMqttClientFactory);
        // Synchronous (the Spring Integration default): QoS 2 is only worth
        // paying for if a failed publish is actually observable by the
        // caller (GeofenceRuleDispatcher) instead of being fired into an
        // async client and forgotten.
        handler.setAsync(false);
        handler.setDefaultQos(ALERT_QOS);
        return handler;
    }
}
