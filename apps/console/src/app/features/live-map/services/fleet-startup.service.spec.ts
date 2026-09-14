import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import type { FleetStateResponse } from '@fleetpulse/api-client';
import { DispatcherSessionControllerService, FleetStateControllerService } from '@fleetpulse/api-client';
import { MqttConnectionService } from '../../../core/mqtt/mqtt-connection.service';
import type { MqttConnectionStatus, MqttInboundMessage } from '../../../core/mqtt/mqtt-connection.models';
import { FleetStartupService } from './fleet-startup.service';
import { FleetStore } from './fleet.store';

class FakeMqttConnectionService {
  private readonly statusSignal = signal<MqttConnectionStatus>('disconnected');
  readonly status = this.statusSignal.asReadonly();
  readonly messages$ = new Subject<MqttInboundMessage>();
  readonly connectCalls: string[] = [];
  readonly disconnectCalls: number[] = [];

  connect(organizationId: string): void {
    this.connectCalls.push(organizationId);
  }

  disconnect(): void {
    this.disconnectCalls.push(1);
  }

  setStatus(status: MqttConnectionStatus): void {
    this.statusSignal.set(status);
  }
}

function telemetryMessage(vehicleId: string, recordedAt: string, lat: number, lon: number): MqttInboundMessage {
  return {
    topic: `fleet/org-1/vehicle/${vehicleId}/telemetry`,
    payload: { recordedAt, lat, lon },
  };
}

describe('FleetStartupService', () => {
  let mqtt: FakeMqttConnectionService;
  let snapshot$: Subject<FleetStateResponse>;
  let service: FleetStartupService;
  let store: InstanceType<typeof FleetStore>;

  beforeEach(() => {
    mqtt = new FakeMqttConnectionService();
    snapshot$ = new Subject<FleetStateResponse>();

    TestBed.configureTestingModule({
      providers: [
        { provide: MqttConnectionService, useValue: mqtt },
        { provide: DispatcherSessionControllerService, useValue: { me: () => of({ organizationId: 'org-1' }) } },
        { provide: FleetStateControllerService, useValue: { state: () => snapshot$ } },
      ],
    });

    service = TestBed.inject(FleetStartupService);
    store = TestBed.inject(FleetStore);
  });

  it('resolves the current dispatcher and connects the MQTT client on start', () => {
    service.start();

    expect(mqtt.connectCalls).toEqual(['org-1']);
  });

  // Task 6.2
  it('applies messages buffered while the snapshot request is in flight, right after the snapshot', () => {
    service.start();
    mqtt.setStatus('connected');
    TestBed.tick();

    mqtt.messages$.next(telemetryMessage('v1', '2026-01-01T00:00:10Z', 10, 20));
    expect(store.vehicles().has('v1')).toBe(false);

    snapshot$.next({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z', online: true }],
    });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:10Z' }),
    );
  });

  // Task 2.3 / Test 6.1
  it('discards a buffered message older than the snapshot for that vehicle', () => {
    service.start();
    mqtt.setStatus('connected');
    TestBed.tick();

    mqtt.messages$.next(telemetryMessage('v1', '2025-12-31T23:59:00Z', 99, 99));

    snapshot$.next({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z', online: true }],
    });

    expect(store.vehicles().get('v1')).toEqual(
      expect.objectContaining({ lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z' }),
    );
  });

  it('applies live messages directly once the snapshot has already been applied', () => {
    service.start();
    mqtt.setStatus('connected');
    TestBed.tick();
    snapshot$.next({ vehicles: [] });

    mqtt.messages$.next(telemetryMessage('v2', '2026-01-01T00:05:00Z', 5, 6));

    expect(store.vehicles().get('v2')).toEqual(expect.objectContaining({ lat: 5, lon: 6 }));
  });

  // Task 2.4
  it('repeats the full snapshot+stream cycle on reconnect', () => {
    service.start();
    mqtt.setStatus('connected');
    TestBed.tick();
    snapshot$.next({
      vehicles: [{ vehicleId: 'v1', lat: 1, lon: 1, recordedAt: '2026-01-01T00:00:00Z' }],
    });

    mqtt.setStatus('reconnecting');
    TestBed.tick();
    mqtt.setStatus('connected');
    TestBed.tick();

    // A message arriving before the fresh post-reconnect snapshot must be
    // buffered again, not applied live -- the cycle restarted from scratch.
    mqtt.messages$.next(telemetryMessage('v2', '2026-01-01T01:00:00Z', 7, 8));
    expect(store.vehicles().has('v2')).toBe(false);

    snapshot$.next({
      vehicles: [{ vehicleId: 'v1', lat: 9, lon: 9, recordedAt: '2026-01-01T02:00:00Z' }],
    });

    expect(store.vehicles().get('v1')).toEqual(expect.objectContaining({ lat: 9, lon: 9 }));
    expect(store.vehicles().get('v2')).toEqual(expect.objectContaining({ lat: 7, lon: 8 }));
  });

  it('stop() disconnects the MQTT client and allows a subsequent start() to run again', () => {
    service.start();
    service.stop();

    expect(mqtt.disconnectCalls.length).toBe(1);

    service.start();
    expect(mqtt.connectCalls).toEqual(['org-1', 'org-1']);
  });
});
