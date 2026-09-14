import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import mqtt, { type MqttClient } from 'mqtt';
import { MqttCredentialsControllerService, type MqttCredentialsResponse } from '@fleetpulse/api-client';
import type { MqttConnectionStatus, MqttInboundMessage } from './mqtt-connection.models';

const VEHICLE_TOPIC_SUFFIXES = ['telemetry', 'status'] as const;

const INITIAL_RECONNECT_DELAY_MS = 1_000;
const MAX_RECONNECT_DELAY_MS = 30_000;
const RECONNECT_BACKOFF_FACTOR = 2;
const CREDENTIAL_RENEWAL_MARGIN_MS = 30_000;
const MIN_RENEWAL_DELAY_MS = 1_000;

interface ResolvedMqttCredentials {
  readonly username: string;
  readonly password: string;
  readonly wsUrl: string;
  readonly expiresAt: string;
}

// Tasks 1.1-1.4: browser MQTT client over WebSocket, connecting with
// credentials from `GET /api/mqtt/credentials`, renewing them before they
// expire, reconnecting with exponential backoff, and subscribing to the
// organization's vehicle telemetry/status topics.
//
// MQTT.js's own `reconnectPeriod` retries at a fixed interval, not an
// exponential one, and reconnecting also needs a *fresh* credential fetch
// (the previous password may already be close to expiry) -- something the
// built-in retry cannot do on its own. `reconnectPeriod` is therefore
// disabled (0) in favor of the manual backoff below.
@Injectable({ providedIn: 'root' })
export class MqttConnectionService implements OnDestroy {
  private readonly credentialsApi = inject(MqttCredentialsControllerService);

  private client: MqttClient | undefined;
  private organizationId: string | undefined;
  private manuallyDisconnected = true;
  private reconnectAttempt = 0;
  private renewalTimer: ReturnType<typeof setTimeout> | undefined;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;

  private readonly statusSignal = signal<MqttConnectionStatus>('disconnected');
  readonly status = this.statusSignal.asReadonly();

  private readonly inboundMessages = new Subject<MqttInboundMessage>();
  readonly messages$ = this.inboundMessages.asObservable();

  connect(organizationId: string): void {
    this.organizationId = organizationId;
    this.manuallyDisconnected = false;
    this.reconnectAttempt = 0;
    this.openConnection();
  }

  disconnect(): void {
    this.manuallyDisconnected = true;
    this.clearRenewalTimer();
    this.clearReconnectTimer();
    const client = this.client;
    this.client = undefined;
    client?.end(true);
    this.statusSignal.set('disconnected');
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  private openConnection(): void {
    if (!this.organizationId || this.manuallyDisconnected) {
      return;
    }
    this.statusSignal.set(this.reconnectAttempt === 0 ? 'connecting' : 'reconnecting');

    this.credentialsApi.credentials().subscribe({
      next: (credentials) => this.handleCredentials(credentials),
      error: () => this.scheduleReconnect(),
    });
  }

  private handleCredentials(credentials: MqttCredentialsResponse): void {
    const resolved = this.resolveCredentials(credentials);
    if (!resolved) {
      this.scheduleReconnect();
      return;
    }
    this.startClient(resolved);
  }

  private resolveCredentials(credentials: MqttCredentialsResponse): ResolvedMqttCredentials | undefined {
    const { username, password, wsUrl, expiresAt } = credentials;
    if (!username || !password || !wsUrl || !expiresAt) {
      return undefined;
    }
    return { username, password, wsUrl, expiresAt };
  }

  private startClient(credentials: ResolvedMqttCredentials): void {
    if (this.manuallyDisconnected || !this.organizationId) {
      return;
    }

    const organizationId = this.organizationId;
    const client = mqtt.connect(credentials.wsUrl, {
      username: credentials.username,
      password: credentials.password,
      clean: true,
      reconnectPeriod: 0,
    });
    this.client = client;

    client.on('connect', () => {
      this.reconnectAttempt = 0;
      this.statusSignal.set('connected');
      this.scheduleCredentialRenewal(credentials.expiresAt);
      this.subscribeToVehicleTopics(client, organizationId);
    });

    client.on('message', (topic, payload) => {
      this.inboundMessages.next({ topic, payload: this.parsePayload(payload) });
    });

    client.on('error', () => {
      // MQTT.js follows a failed-connection 'error' with a 'close' event;
      // the reconnect scheduling below reacts to 'close', not to this one.
    });

    client.on('close', () => {
      if (this.client !== client) {
        return;
      }
      this.client = undefined;
      this.clearRenewalTimer();
      if (this.manuallyDisconnected) {
        this.statusSignal.set('disconnected');
        return;
      }
      this.scheduleReconnect();
    });
  }

  private subscribeToVehicleTopics(client: MqttClient, organizationId: string): void {
    const topics = VEHICLE_TOPIC_SUFFIXES.map((suffix) => `fleet/${organizationId}/vehicle/+/${suffix}`);
    client.subscribe(topics);
  }

  private scheduleCredentialRenewal(expiresAt: string): void {
    this.clearRenewalTimer();
    const ttlMs = new Date(expiresAt).getTime() - Date.now();
    const renewInMs = Math.max(ttlMs - CREDENTIAL_RENEWAL_MARGIN_MS, MIN_RENEWAL_DELAY_MS);
    this.renewalTimer = setTimeout(() => this.renewCredentials(), renewInMs);
  }

  private renewCredentials(): void {
    if (this.manuallyDisconnected || !this.client) {
      return;
    }
    // `this.client` is cleared before `end()` so the 'close' handler's
    // `this.client !== client` check already sees it as stale and skips
    // scheduling a reconnect -- `openConnection()` below opens the
    // replacement with freshly-fetched credentials instead.
    const staleClient = this.client;
    this.client = undefined;
    staleClient.end(true);
    this.openConnection();
  }

  private scheduleReconnect(): void {
    this.clearReconnectTimer();
    const delay = Math.min(
      INITIAL_RECONNECT_DELAY_MS * RECONNECT_BACKOFF_FACTOR ** this.reconnectAttempt,
      MAX_RECONNECT_DELAY_MS,
    );
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => this.openConnection(), delay);
  }

  private clearRenewalTimer(): void {
    if (this.renewalTimer !== undefined) {
      clearTimeout(this.renewalTimer);
      this.renewalTimer = undefined;
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== undefined) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
  }

  private parsePayload(payload: Buffer): unknown {
    try {
      return JSON.parse(payload.toString());
    } catch {
      return undefined;
    }
  }
}
