import { test, expect, type Page } from '@playwright/test';
import type { MqttClient } from 'mqtt';
import { TEST_ORG_ID, stubFleetApi } from './support/live-map-stubs';
import { connectTestPublisher, endTestPublisher, publishTelemetry } from './support/mqtt-test-publisher';

// Test 6.5: "un mensaje MQTT publicado por el test mueve el marcador en
// pantalla". Publishes through a REAL Node-side MQTT client onto the REAL
// local Mosquitto broker (docker-compose's `mosquitto` service) that the
// console's browser MQTT client (WU1) also connects to over WebSocket -- see
// support/live-map-stubs.ts for the scoping decision on why `api` itself is
// stubbed at the HTTP layer rather than run as a real process for this test.
const VEHICLE_ID = 'e2e-vehicle-1';
const INITIAL_LAT = 40.4168;
const INITIAL_LON = -3.7038;
const MOVED_LAT = 40.4268;
const MOVED_LON = -3.6938;

test.describe('Live map -- real MQTT message moves the marker', () => {
  let publisher: MqttClient;

  test.beforeAll(async () => {
    publisher = await connectTestPublisher();
  });

  test.afterAll(async () => {
    await endTestPublisher(publisher);
  });

  test('a real broker message updates the reported state and moves the rendered marker', async ({ page }) => {
    await stubFleetApi(page, [
      {
        vehicleId: VEHICLE_ID,
        label: 'E2E Vehicle',
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

    // Sanity check the marker actually renders at the reported position
    // before anything is published, so a later "moved" assertion cannot pass
    // vacuously against an empty/never-rendered layer.
    await expect.poll(() => hasFeatureNear(page, VEHICLE_ID, INITIAL_LAT, INITIAL_LON), { timeout: 10_000 }).toBe(true);

    await publishTelemetry(publisher, TEST_ORG_ID, VEHICLE_ID, {
      recordedAt: new Date().toISOString(),
      lat: MOVED_LAT,
      lon: MOVED_LON,
      speedKmh: 42,
      heading: 90,
    });

    // The detail panel always shows the exact reported (never interpolated)
    // position -- proves the real broker message reached FleetStore.
    await expect(page.getByTestId('vehicle-detail-position')).toContainText(String(MOVED_LAT), { timeout: 10_000 });

    // The map's rendered symbol layer eventually settles on the same
    // coordinates too, once the interpolation window (task 4.4/4.5, 5s)
    // finishes -- proves the marker itself moved on the rendered map, not
    // only the side panel's text.
    await expect
      .poll(() => hasFeatureNear(page, VEHICLE_ID, MOVED_LAT, MOVED_LON), { timeout: 8_000, intervals: [500] })
      .toBe(true);
  });
});

async function hasFeatureNear(page: Page, vehicleId: string, lat: number, lon: number): Promise<boolean> {
  const coordinates = await page.evaluate((id: string) => {
    const map = (window as unknown as { __fleetpulseLiveMap?: import('maplibre-gl').Map }).__fleetpulseLiveMap;
    if (!map) {
      return null;
    }
    const features = map.queryRenderedFeatures(undefined, {
      layers: ['vehicles-layer'],
      filter: ['==', ['get', 'vehicleId'], id],
    });
    const geometry = features[0]?.geometry;
    return geometry && geometry.type === 'Point' ? (geometry.coordinates as [number, number]) : null;
  }, vehicleId);
  if (!coordinates) {
    return false;
  }
  const [renderedLon, renderedLat] = coordinates;
  return Math.abs(renderedLat - lat) < 1e-6 && Math.abs(renderedLon - lon) < 1e-6;
}
