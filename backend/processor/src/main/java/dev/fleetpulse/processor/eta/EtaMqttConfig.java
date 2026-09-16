package dev.fleetpulse.processor.eta;

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

// Task 2.4: a DEDICATED outbound connection identity, not a reuse of
// MqttClientFactoryConfig's shared short-lived/fail-fast factory NOR of
// AlertMqttConfig's own long-lived outbound one, for two concrete reasons
// documented per this project's "note deviations, don't silently freelance"
// convention (checked AlertMqttConfig/TelemetryMqttConfig first, per the
// launch prompt's own instruction, before adding this):
//
// 1. Volume/QoS profile: ETA recalculates "con cada posicion" (design.md) --
//    per eligible LIVE telemetry message for every vehicle with an assigned
//    destination, the same cadence as telemetry itself, not alerts' sparse
//    episodic events. QoS 0 (TELEMETRY_QOS's own justification: only the
//    LATEST value matters, a dropped stale estimate is immediately
//    superseded by the next live position) is correct here and wrong for
//    alerts' QoS 2 ("this topic pays the exactly-once delivery cost",
//    AlertMqttConfig's own comment) -- reusing that channel would mean
//    either forcing ETA onto QoS 2 it does not need, or complicating a
//    single adapter with a per-message QoS override this codebase's MQTT
//    config classes have never needed before (TelemetryMqttConfig/
//    AlertMqttConfig both hardcode one fixed QoS per adapter).
// 2. Sync/async: AlertMqttConfig's handler is deliberately synchronous
//    ("QoS 2 is only worth paying for if a failed publish is observable by
//    the caller"). At ETA's much higher publish frequency, a synchronous
//    publish per eligible message would add latency directly onto
//    JdbcTelemetryPositionWriter's guarded write path -- design.md is
//    explicit that path must stay "centrada en escribir rapido". Async here
//    keeps a slow/unreachable broker from ever blocking telemetry ingestion,
//    matching this same file's own already-established "never let a
//    best-effort publish break the write path" precedent
//    (GeofenceRuleDispatcher's own try/catch around alertPublisher.publish).
//
// No new ACL registration is needed (same reasoning as AlertMqttConfig's own
// comment): fleet/{orgId}/vehicle/{vehicleId}/eta is already covered by
// MqttClientFactoryConfig's shared "internal-services" wildcard
// publishClientSend '#' and BrowserMqttCredentialService's dispatcher
// subscribePattern fleet/{orgId}/# ACLs.
@Configuration
public class EtaMqttConfig {

    static final String ETA_OUTPUT_CHANNEL = "etaOutputChannel";
    static final int ETA_QOS = 0;

    @Bean(name = ETA_OUTPUT_CHANNEL)
    public MessageChannel etaOutputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoClientFactory etaOutboundMqttClientFactory(FleetpulseMqttServiceCredentialsProperties serviceCredentials) {
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
    @ServiceActivator(inputChannel = ETA_OUTPUT_CHANNEL)
    public MessageHandler etaOutboundMqttHandler(
        FleetpulseMqttProperties mqttProperties,
        MqttPahoClientFactory etaOutboundMqttClientFactory
    ) {
        String clientId = "fleetpulse-processor-eta-" + UUID.randomUUID();
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(mqttProperties.brokerUrl(), clientId, etaOutboundMqttClientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(ETA_QOS);
        return handler;
    }
}
