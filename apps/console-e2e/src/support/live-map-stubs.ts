import type { Page } from '@playwright/test';

// Scoping decision (WU6, tests 6.5/6.6, documented in tasks.md): bringing up
// the full stack (api + processor + PostGIS + Mosquitto) for a browser E2E
// run is out of scope for this work unit -- api/mqtt/credentials,
// api/dispatchers/me, api/fleet/state and api/vehicles/{id}/track are backed
// by their own Testcontainers coverage already (WU2's
// FleetStateEndpointTest/VehicleTrackEndpointTest,
// CrossOrganizationMqttIsolationTest, etc.), which genuinely exercises the
// api<->PostGIS<->Mosquitto wiring. What those tests cannot exercise is the
// real browser MQTT client + FleetStore + MapLibre rendering pipeline this
// change actually adds -- that is what this E2E layer proves instead, against
// a REAL local Mosquitto broker (docker-compose's `mosquitto` service) with
// the four `api` HTTP endpoints stubbed at the Playwright network layer.
//
// `internal-services` is the SAME fixed dev identity docker-compose.yml's
// own bootstrap-and-run.sh already provisions with a wildcard `#`
// publish/subscribe role (used by this repo's own health checks and processor
// heartbeat) -- reused here as the test-side MQTT publisher's credentials
// rather than inventing a second dynsec identity, and also handed back to the
// browser client as its stubbed `GET /api/mqtt/credentials` response so the
// two sides of the test share one real, already-authorized broker identity.
export const TEST_ORG_ID = 'org-1';
export const TEST_MQTT_USERNAME = 'internal-services';
export const TEST_MQTT_PASSWORD = 'fleetpulse-dynsec-service';
export const TEST_BROKER_TCP_URL = 'mqtt://localhost:1883';
export const TEST_BROKER_WS_URL = 'ws://localhost:9001';

export interface StubbedVehicle {
  readonly vehicleId: string;
  readonly label?: string;
  readonly lat?: number;
  readonly lon?: number;
  readonly recordedAt?: string;
  readonly motionState?: 'MOVING' | 'IDLING' | 'STOPPED';
  readonly online?: boolean;
}

// Registers the four api/* routes LiveMapPageComponent's startup sequence
// depends on (FleetStartupService.start(): dispatchers/me -> mqtt/credentials
// -> fleet/state; VehicleTrackService's httpResource: vehicles/{id}/track).
// Also arms `LiveMapComponent`'s E2E-only `window.__fleetpulseLiveMap`
// hook via an init script (must run before any page script, hence before
// `page.goto()`) -- see that component's `isE2eHarness()` doc comment: the
// flag is set only for an explicit, opted-in E2E run like this one, never
// for a real deployment.
export async function stubFleetApi(page: Page, initialVehicles: readonly StubbedVehicle[] = []) {
  await page.addInitScript(() => {
    (window as unknown as { __fleetpulseE2E?: boolean }).__fleetpulseE2E = true;
  });

  await page.route('**/api/dispatchers/me', (route) =>
    route.fulfill({
      json: { id: 'dispatcher-1', organizationId: TEST_ORG_ID, email: 'dispatcher@example.com', role: 'DISPATCHER' },
    }),
  );

  await page.route('**/api/mqtt/credentials', (route) =>
    route.fulfill({
      json: {
        username: TEST_MQTT_USERNAME,
        password: TEST_MQTT_PASSWORD,
        wsUrl: TEST_BROKER_WS_URL,
        expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      },
    }),
  );

  await page.route('**/api/fleet/state', (route) =>
    route.fulfill({ json: { vehicles: initialVehicles } }),
  );

  await page.route('**/api/vehicles/*/track', (route) => route.fulfill({ json: [] }));
}

// DoD 2: simulates "the api process is stopped" at the only boundary a
// browser can actually observe it from -- every further /api/** call fails.
// Never touches the mosquitto routes/ports, matching the real architecture
// (live updates arrive over a direct browser<->broker WebSocket, independent
// of api).
export async function breakApiHttp(page: Page) {
  await page.unroute('**/api/dispatchers/me');
  await page.unroute('**/api/mqtt/credentials');
  await page.unroute('**/api/fleet/state');
  await page.unroute('**/api/vehicles/*/track');
  await page.route('**/api/**', (route) => route.abort('connectionrefused'));
}
