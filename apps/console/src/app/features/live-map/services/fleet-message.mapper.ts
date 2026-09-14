import type { MqttInboundMessage } from '../../../core/mqtt/mqtt-connection.models';

// Matches processor's TelemetryPayloadParser/PresencePayloadParser topic
// patterns (fleet/{orgId}/vehicle/{vehicleId}/telemetry|status); group 1 is
// the vehicle segment, same as the backend.
const TELEMETRY_TOPIC_PATTERN = /^fleet\/[^/]+\/vehicle\/([^/]+)\/telemetry$/;
const STATUS_TOPIC_PATTERN = /^fleet\/[^/]+\/vehicle\/([^/]+)\/status$/;

export interface VehicleTelemetryUpdate {
  readonly kind: 'telemetry';
  readonly vehicleId: string;
  readonly recordedAt: string;
  readonly lat: number;
  readonly lon: number;
  readonly speedKmh?: number;
  readonly heading?: number;
}

export interface VehiclePresenceUpdate {
  readonly kind: 'presence';
  readonly vehicleId: string;
  readonly online: boolean;
}

export type VehicleUpdate = VehicleTelemetryUpdate | VehiclePresenceUpdate;

// Turns a raw MqttInboundMessage into a validated VehicleUpdate, or discards
// it (returns undefined) if the topic/payload doesn't match the wire
// contract -- vehicleId always comes from the topic, never the payload body,
// mirroring TelemetryPayloadParser/PresencePayloadParser. A browser client
// has no dead-letter path, so one malformed message must never break the
// live stream for every other vehicle; it is silently dropped instead of
// throwing.
export function mapInboundMessage(message: MqttInboundMessage): VehicleUpdate | undefined {
  const telemetryMatch = TELEMETRY_TOPIC_PATTERN.exec(message.topic);
  if (telemetryMatch) {
    return mapTelemetry(telemetryMatch[1], message.payload);
  }
  const statusMatch = STATUS_TOPIC_PATTERN.exec(message.topic);
  if (statusMatch) {
    return mapPresence(statusMatch[1], message.payload);
  }
  return undefined;
}

function mapTelemetry(vehicleId: string, payload: unknown): VehicleTelemetryUpdate | undefined {
  if (!isRecord(payload)) {
    return undefined;
  }
  const { recordedAt, lat, lon, speedKmh, heading } = payload;
  if (typeof recordedAt !== 'string' || typeof lat !== 'number' || typeof lon !== 'number') {
    return undefined;
  }
  return {
    kind: 'telemetry',
    vehicleId,
    recordedAt,
    lat,
    lon,
    speedKmh: typeof speedKmh === 'number' ? speedKmh : undefined,
    heading: typeof heading === 'number' ? heading : undefined,
  };
}

function mapPresence(vehicleId: string, payload: unknown): VehiclePresenceUpdate | undefined {
  if (!isRecord(payload) || typeof payload['online'] !== 'boolean') {
    return undefined;
  }
  return { kind: 'presence', vehicleId, online: payload['online'] };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
