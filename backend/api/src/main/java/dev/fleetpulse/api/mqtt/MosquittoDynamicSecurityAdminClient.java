package dev.fleetpulse.api.mqtt;

import dev.fleetpulse.api.config.FleetpulseMqttDynsecProperties;
import dev.fleetpulse.api.config.FleetpulseMqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Task 5.1: drives Mosquitto's built-in dynamic-security plugin over its
// $CONTROL/dynamic-security/v1 MQTT control topic -- see design.md's
// confirmed deviation note (this repo's tasks.md/apply-progress) for why
// this, and not a custom Mosquitto auth plugin, is the credential/ACL
// backend. Each call is its own short-lived connect-publish-await-disconnect
// round trip, matching this module's existing MQTT client style
// (MqttBrokerHealthIndicator, ProcessorHeartbeatPublisher): the dynsec admin
// identity is deliberately never reused for ordinary application topics
// (its role only grants $CONTROL/dynamic-security/# and $SYS/# access, not
// arbitrary publish/subscribe -- confirmed empirically against a real
// broker), so it cannot share the generic MqttClientFactoryConfig bean's
// connect options either.
@Component
public class MosquittoDynamicSecurityAdminClient {

    private static final String COMMAND_TOPIC = "$CONTROL/dynamic-security/v1";
    private static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;
    private static final int RESPONSE_TIMEOUT_SECONDS = 5;
    private static final String ALREADY_EXISTS_MARKER = "already exists";

    private final MqttPahoClientFactory clientFactory;
    private final FleetpulseMqttProperties mqttProperties;
    private final FleetpulseMqttDynsecProperties dynsecProperties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public MosquittoDynamicSecurityAdminClient(
            MqttPahoClientFactory clientFactory,
            FleetpulseMqttProperties mqttProperties,
            FleetpulseMqttDynsecProperties dynsecProperties) {
        this.clientFactory = clientFactory;
        this.mqttProperties = mqttProperties;
        this.dynsecProperties = dynsecProperties;
    }

    public void createClient(String username, String rawPassword) {
        execute("createClient", Map.of("username", username, "password", rawPassword));
    }

    // Idempotent: dynsec has no "createRole if absent" primitive, so a
    // "<role> already exists" error response is treated as success instead
    // of round-tripping a separate existence check first.
    public void createRoleIfMissing(String roleName) {
        JsonNode response = executeAllowingError("createRole", Map.of("rolename", roleName));
        String error = errorOf(response);
        if (error != null && !error.toLowerCase(java.util.Locale.ROOT).contains(ALREADY_EXISTS_MARKER)) {
            throw new MosquittoDynamicSecurityException("createRole", error);
        }
    }

    public void addSubscribeAcl(String roleName, String topicFilter, boolean allow) {
        execute("addRoleACL", Map.of(
            "rolename", roleName,
            "acltype", "subscribePattern",
            "topic", topicFilter,
            "allow", allow));
    }

    public void addClientRole(String username, String roleName) {
        execute("addClientRole", Map.of("username", username, "rolename", roleName));
    }

    public void deleteClient(String username) {
        execute("deleteClient", Map.of("username", username));
    }

    private void execute(String command, Map<String, Object> fields) {
        JsonNode response = executeAllowingError(command, fields);
        String error = errorOf(response);
        if (error != null) {
            throw new MosquittoDynamicSecurityException(command, error);
        }
    }

    private JsonNode executeAllowingError(String command, Map<String, Object> fields) {
        if (!StringUtils.hasText(dynsecProperties.adminUsername()) || !StringUtils.hasText(dynsecProperties.adminPassword())) {
            throw new MosquittoDynamicSecurityException(command, "Dynamic-security admin credentials are not configured");
        }
        String correlationId = UUID.randomUUID().toString();
        String clientId = "fleetpulse-dynsec-admin-" + UUID.randomUUID();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        try {
            IMqttClient client = clientFactory.getClientInstance(mqttProperties.brokerUrl(), clientId);
            try {
                client.setCallback(responseListener(correlationId, responseFuture));
                client.connect(adminConnectOptions());
                try {
                    client.subscribe(RESPONSE_TOPIC, 1);
                    client.publish(COMMAND_TOPIC, requestMessage(command, fields, correlationId));
                    return responseFuture.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } finally {
                    client.disconnect();
                }
            } finally {
                client.close();
            }
        } catch (MqttException ex) {
            throw new MosquittoDynamicSecurityException(command, "MQTT error: " + ex.getMessage(), ex);
        } catch (TimeoutException ex) {
            throw new MosquittoDynamicSecurityException(command, "Timed out waiting for a response", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MosquittoDynamicSecurityException(command, "Interrupted while waiting for a response", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new MosquittoDynamicSecurityException(command, "Failed while waiting for a response", ex.getCause());
        }
    }

    private MqttConnectOptions adminConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        options.setUserName(dynsecProperties.adminUsername());
        options.setPassword(dynsecProperties.adminPassword().toCharArray());
        return options;
    }

    private MqttMessage requestMessage(String command, Map<String, Object> fields, String correlationId) {
        Map<String, Object> commandPayload = new LinkedHashMap<>(fields);
        commandPayload.put("command", command);
        commandPayload.put("correlationData", correlationId);
        String json = jsonMapper.writeValueAsString(Map.of("commands", List.of(commandPayload)));
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        return message;
    }

    private MqttCallback responseListener(String correlationId, CompletableFuture<JsonNode> responseFuture) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                responseFuture.completeExceptionally(cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                JsonNode root = jsonMapper.readTree(message.getPayload());
                for (JsonNode response : root.path("responses")) {
                    if (correlationId.equals(textOrNull(response.path("correlationData")))) {
                        responseFuture.complete(response);
                        return;
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        };
    }

    private static String errorOf(JsonNode response) {
        return textOrNull(response.path("error"));
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
