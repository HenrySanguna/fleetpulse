import { test, expect } from '@playwright/test';
import type { MqttClient } from 'mqtt';
import { TEST_ORG_ID, breakApiHttp, stubFleetApi } from './support/live-map-stubs';
import { connectTestPublisher, endTestPublisher, publishTelemetry } from './support/mqtt-test-publisher';

// Definicion de terminado: "Detener el proceso api con el mapa abierto NO
// congela las actualizaciones en vivo (siguen llegando por MQTT)".
//
// Architecture reasoning (design.md, "Independencia del mapa en vivo
// respecto al servicio HTTP"; proposal.md's core decision): once
// FleetStartupService.start() has resolved the dispatcher, connected MQTT
// and applied the first snapshot, the ONLY things left that ever call `api`
// again are (1) credential renewal ~30s before expiry (task 1.2) and (2) the
// selected vehicle's track httpResource -- everything else (every telemetry
// and presence update) flows browser<->broker directly over the WebSocket
// MqttConnectionService already holds, never touching `api`. From the
// browser's side, "the api process is stopped" and "every further /api/**
// call fails" are the exact same observable event, which is what this test
// forces via breakApiHttp() (see support/live-map-stubs.ts) after startup
// has already completed -- proving the claim with a real check rather than
// narrative alone, consistent with this WU's documented scoping decision to
// stub `api` at the HTTP layer instead of running the real process.
const VEHICLE_ID = 'e2e-vehicle-http-independence';
const INITIAL_LAT = 48.8566;
const INITIAL_LON = 2.3522;
const LIVE_LAT = 48.8666;
const LIVE_LON = 2.3622;

test.describe('Live map -- keeps updating live when the api HTTP boundary is unavailable', () => {
  let publisher: MqttClient;

  test.beforeAll(async () => {
    publisher = await connectTestPublisher();
  });

  test.afterAll(async () => {
    await endTestPublisher(publisher);
  });

  test('MQTT-sourced updates keep landing after every /api/** call starts failing', async ({ page }) => {
    await stubFleetApi(page, [
      {
        vehicleId: VEHICLE_ID,
        label: 'HTTP Independence Vehicle',
        lat: INITIAL_LAT,
        lon: INITIAL_LON,
        recordedAt: new Date().toISOString(),
        motionState: 'STOPPED',
        online: true,
      },
    ]);

    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toHaveText('Connected', { timeout: 15_000 });
    await page.getByRole('option').filter({ hasText: VEHICLE_ID }).click();
    await expect(page.getByTestId('vehicle-detail-position')).toContainText(String(INITIAL_LAT));

    // Simulates `api` being stopped: startup already finished, so this only
    // affects credential renewal and the track httpResource -- neither of
    // which the live vehicle stream depends on.
    await breakApiHttp(page);

    // The track resource's own failure must stay contained -- selecting the
    // vehicle again (re-running the httpResource request) must not throw or
    // freeze the page; the map keeps working regardless of this failing.
    await page.getByRole('option').filter({ hasText: VEHICLE_ID }).click();

    await publishTelemetry(publisher, TEST_ORG_ID, VEHICLE_ID, {
      recordedAt: new Date().toISOString(),
      lat: LIVE_LAT,
      lon: LIVE_LON,
    });

    await expect(page.getByTestId('vehicle-detail-position')).toContainText(String(LIVE_LAT), { timeout: 10_000 });
    // The connection badge itself never depends on `api` either -- it only
    // reflects MqttConnectionService.status, unaffected by breakApiHttp().
    await expect(page.getByTestId('connection-status')).toHaveText('Connected');
  });
});
