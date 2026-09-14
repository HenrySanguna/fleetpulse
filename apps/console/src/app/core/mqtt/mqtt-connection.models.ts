export type MqttConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

export interface MqttInboundMessage {
  readonly topic: string;
  readonly payload: unknown;
}
