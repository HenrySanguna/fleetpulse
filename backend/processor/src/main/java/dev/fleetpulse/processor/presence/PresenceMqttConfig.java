package dev.fleetpulse.processor.presence;

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

// Task 5.1: the presence/testament contract every telemetry device MUST
// register when it connects to the broker. design.md ("Detección de offline
// con Last Will and Testament") specifies the topic, payload shape, and that
// retain must be true; this Javadoc is the normative source for the
// remaining details design.md leaves open -- QoS and the exact connect-time
// sequence a device must follow.
//
//   Topic:   fleet/{orgId}/vehicle/{vehicleId}/status
//   QoS:     1 (at-least-once). "online" is idempotent -- applying the same
//            value twice is harmless -- but QoS 0 could silently drop the
//            transition entirely, leaving a vehicle wrongly marked online or
//            offline with nothing to correct it (design.md is explicit that
//            presence must not depend on an inactivity timer of its own).
//            This mirrors design.md's own QoS table reasoning for "command"
//            (must arrive, duplicate tolerable if idempotent); status is not
//            literally telemetry/command/alerts, so this project extends
//            that same reasoning to it here.
//   Retain:  true on EVERY publish to this topic -- both the will and the
//            device's own online announcement. A dispatcher console that
//            subscribes after the fact must see the vehicle's last known
//            state immediately (design.md), not wait for the next change.
//   Payload: {"online": <boolean>}
//
// Registration sequence a device MUST follow on every connect():
//   1. Before calling connect(), set the MQTT Last Will and Testament to
//      topic=fleet/{orgId}/vehicle/{vehicleId}/status, payload={"online":false},
//      qos=1, retain=true. This is what the broker publishes automatically if
//      the device disconnects without a clean DISCONNECT (power loss, lost
//      coverage, cable pulled) -- test 6.6.
//   2. Immediately after connect() succeeds, PUBLISH (not via the will) the
//      same topic with payload={"online":true}, qos=1, retain=true. This
//      overwrites whatever was retained before (possibly the offline
//      testament from the device's own last ungraceful disconnect) -- test 6.7.
//
// PresenceMessageListener below subscribes to the wildcard
// fleet/+/vehicle/+/status and applies either publish -- the will or the
// device's own announcement, wire-indistinguishable and not meant to be
// told apart -- to vehicle_state.online.
@Configuration
public class PresenceMqttConfig {

    static final String STATUS_TOPIC_FILTER = "fleet/+/vehicle/+/status";
    static final int STATUS_QOS = 1;
    static final String PRESENCE_INPUT_CHANNEL = "presenceInputChannel";

    @Bean(name = PRESENCE_INPUT_CHANNEL)
    public MessageChannel presenceInputChannel() {
        return new DirectChannel();
    }

    // A dedicated factory, not a reuse of telemetry's: presence is a
    // logically independent topic (tasks.md, WU8) with no coupling to the
    // telemetry ingest pipeline, including at the infrastructure level. Same
    // long-lived, auto-reconnecting shape as TelemetryMqttConfig's own
    // factory, since this adapter also holds one persistent subscription.
    @Bean
    public MqttPahoClientFactory presenceInboundMqttClientFactory(FleetpulseMqttServiceCredentialsProperties serviceCredentials) {
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
    public MqttPahoMessageDrivenChannelAdapter presenceInboundAdapter(
        FleetpulseMqttProperties mqttProperties,
        MqttPahoClientFactory presenceInboundMqttClientFactory,
        MessageChannel presenceInputChannel
    ) {
        String clientId = "fleetpulse-processor-presence-" + UUID.randomUUID();
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
            mqttProperties.brokerUrl(), clientId, presenceInboundMqttClientFactory, STATUS_TOPIC_FILTER
        );
        adapter.setQos(STATUS_QOS);
        adapter.setOutputChannel(presenceInputChannel);
        return adapter;
    }
}
