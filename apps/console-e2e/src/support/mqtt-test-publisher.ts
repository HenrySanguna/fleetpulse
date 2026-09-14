import mqtt, { type MqttClient } from 'mqtt';
import { TEST_BROKER_TCP_URL, TEST_MQTT_PASSWORD, TEST_MQTT_USERNAME } from './live-map-stubs';

// Test-side MQTT client (Node, over plain TCP) publishing to the SAME real
// local Mosquitto broker the browser client connects to over WebSocket --
// same pattern 03-add-telemetry-ingest's TelemetryEndToEndIngestTest and the
// dev device simulator use (a real MqttClient publishing real wire payloads),
// just from Playwright's Node side instead of a JVM test.
export function connectTestPublisher(): Promise<MqttClient> {
  return new Promise((resolve, reject) => {
    const client = mqtt.connect(TEST_BROKER_TCP_URL, {
      username: TEST_MQTT_USERNAME,
      password: TEST_MQTT_PASSWORD,
      clean: true,
      connectTimeout: 5_000,
    });
    client.once('connect', () => resolve(client));
    client.once('error', (error) => reject(error));
  });
}

export function publishTelemetry(
  client: MqttClient,
  orgId: string,
  vehicleId: string,
  payload: { readonly recordedAt: string; readonly lat: number; readonly lon: number; readonly speedKmh?: number; readonly heading?: number },
): Promise<void> {
  return new Promise((resolve, reject) => {
    client.publish(`fleet/${orgId}/vehicle/${vehicleId}/telemetry`, JSON.stringify(payload), (error) =>
      error ? reject(error) : resolve(),
    );
  });
}

export function publishStatus(client: MqttClient, orgId: string, vehicleId: string, online: boolean): Promise<void> {
  return new Promise((resolve, reject) => {
    client.publish(`fleet/${orgId}/vehicle/${vehicleId}/status`, JSON.stringify({ online }), (error) =>
      error ? reject(error) : resolve(),
    );
  });
}

export function endTestPublisher(client: MqttClient): Promise<void> {
  return new Promise((resolve) => client.end(false, {}, () => resolve()));
}
