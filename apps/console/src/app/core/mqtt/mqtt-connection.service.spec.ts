import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MqttCredentialsControllerService } from '@fleetpulse/api-client';
import { MqttConnectionService } from './mqtt-connection.service';

type Handler = (...args: unknown[]) => void;

const { mockConnect, fakeClients } = vi.hoisted(() => {
  class FakeMqttClient {
    private readonly listeners = new Map<string, Handler[]>();
    readonly subscribeCalls: string[][] = [];
    readonly endCalls: Array<boolean | undefined> = [];

    on(event: string, handler: Handler): this {
      const handlers = this.listeners.get(event) ?? [];
      handlers.push(handler);
      this.listeners.set(event, handlers);
      return this;
    }

    fire(event: string, ...args: unknown[]): void {
      for (const handler of this.listeners.get(event) ?? []) {
        handler(...args);
      }
    }

    subscribe(topics: string[]): this {
      this.subscribeCalls.push(topics);
      return this;
    }

    end(force?: boolean): this {
      this.endCalls.push(force);
      return this;
    }
  }

  const fakeClients: FakeMqttClient[] = [];
  const mockConnect = vi.fn(() => {
    const client = new FakeMqttClient();
    fakeClients.push(client);
    return client;
  });

  return { mockConnect, fakeClients };
});

vi.mock('mqtt', () => ({ connect: mockConnect }));

const ORG_ID = 'org-1';
const CREDENTIALS = {
  username: 'browser-abc',
  password: 'secret',
  wsUrl: 'wss://broker.fleetpulse.dev/mqtt',
  expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
};

describe('MqttConnectionService', () => {
  let credentialsApi: { credentials: ReturnType<typeof vi.fn> };
  let service: MqttConnectionService;

  beforeEach(() => {
    mockConnect.mockClear();
    fakeClients.length = 0;
    credentialsApi = { credentials: vi.fn(() => of(CREDENTIALS)) };

    TestBed.configureTestingModule({
      providers: [{ provide: MqttCredentialsControllerService, useValue: credentialsApi }],
    });
    service = TestBed.inject(MqttConnectionService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Task 1.1
  it('fetches credentials and connects over WebSocket with a disabled built-in reconnect period', () => {
    service.connect(ORG_ID);

    expect(credentialsApi.credentials).toHaveBeenCalledTimes(1);
    expect(mockConnect).toHaveBeenCalledWith(CREDENTIALS.wsUrl, {
      username: CREDENTIALS.username,
      password: CREDENTIALS.password,
      clean: true,
      reconnectPeriod: 0,
    });
  });

  // Task 1.4
  it('subscribes to the organization telemetry and status topics once connected', () => {
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');

    expect(fakeClients[0].subscribeCalls).toEqual([
      [`fleet/${ORG_ID}/vehicle/+/telemetry`, `fleet/${ORG_ID}/vehicle/+/status`],
    ]);
    expect(service.status()).toBe('connected');
  });

  it('parses inbound message payloads as JSON and republishes them on messages$', () => {
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');

    const received: unknown[] = [];
    service.messages$.subscribe((message) => received.push(message));

    fakeClients[0].fire('message', 'fleet/org-1/vehicle/v1/telemetry', Buffer.from('{"lat":1,"lon":2}'));

    expect(received).toEqual([
      { topic: 'fleet/org-1/vehicle/v1/telemetry', payload: { lat: 1, lon: 2 } },
    ]);
  });

  // Task 1.2
  it('renews credentials proactively before they expire, opening a fresh connection', () => {
    vi.useFakeTimers();
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');
    expect(credentialsApi.credentials).toHaveBeenCalledTimes(1);

    // expiresAt is 5 minutes out; the 30s renewal margin fires at ~4m30s.
    vi.advanceTimersByTime(4 * 60_000 + 30_000);

    expect(fakeClients[0].endCalls).toEqual([true]);
    expect(credentialsApi.credentials).toHaveBeenCalledTimes(2);
    expect(mockConnect).toHaveBeenCalledTimes(2);
  });

  // Task 1.3
  it('reconnects after an unexpected close with exponentially growing backoff', () => {
    vi.useFakeTimers();
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');

    fakeClients[0].fire('close');
    expect(mockConnect).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(999);
    expect(mockConnect).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(mockConnect).toHaveBeenCalledTimes(2);
    expect(service.status()).toBe('reconnecting');

    fakeClients[1].fire('close');
    vi.advanceTimersByTime(1999);
    expect(mockConnect).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1);
    expect(mockConnect).toHaveBeenCalledTimes(3);
  });

  it('caps the reconnect backoff delay instead of growing it without bound', () => {
    vi.useFakeTimers();
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');

    for (let attempt = 0; attempt < 6; attempt++) {
      fakeClients[fakeClients.length - 1].fire('close');
      vi.advanceTimersByTime(30_000);
    }

    expect(mockConnect).toHaveBeenCalledTimes(7);
    fakeClients[fakeClients.length - 1].fire('close');
    vi.advanceTimersByTime(29_999);
    expect(mockConnect).toHaveBeenCalledTimes(7);
    vi.advanceTimersByTime(1);
    expect(mockConnect).toHaveBeenCalledTimes(8);
  });

  it('retries with backoff when the credentials request itself fails', () => {
    vi.useFakeTimers();
    credentialsApi.credentials.mockReturnValueOnce(throwError(() => new Error('network down')));

    service.connect(ORG_ID);
    expect(mockConnect).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1_000);
    expect(credentialsApi.credentials).toHaveBeenCalledTimes(2);
    expect(mockConnect).toHaveBeenCalledTimes(1);
  });

  it('does not reconnect after an explicit disconnect', () => {
    vi.useFakeTimers();
    service.connect(ORG_ID);
    fakeClients[0].fire('connect');

    service.disconnect();
    expect(fakeClients[0].endCalls).toEqual([true]);
    expect(service.status()).toBe('disconnected');

    fakeClients[0].fire('close');
    vi.advanceTimersByTime(60_000);
    expect(mockConnect).toHaveBeenCalledTimes(1);
  });
});
