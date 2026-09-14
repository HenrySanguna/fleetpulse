import { test, expect, type WebSocketRoute } from '@playwright/test';
import type { MqttClient } from 'mqtt';
import { TEST_BROKER_WS_URL, TEST_ORG_ID, stubFleetApi } from './support/live-map-stubs';
import { connectTestPublisher, endTestPublisher, publishTelemetry } from './support/mqtt-test-publisher';

// Test 6.6: "al perder la conexión, la interfaz muestra el aviso; al
// recuperarla, el estado se resincroniza".
//
// `context.setOffline(true)` was tried first and does NOT close an already
// established WebSocket in this Chromium build -- the connection-status
// badge stayed "Connected" for the whole wait window, confirmed empirically
// before switching approaches. A plain `routeWebSocket().close()` was tried
// next and DOES close the real connection for real (confirmed: MqttClient
// genuinely opens a brand new WebSocket afterward), but in this fast local
// stub-everything setup the reconnect completes well under 200ms -- too
// fast for the status badge's "Reconnecting"/"Disconnected" text to ever
// land on a Playwright poll tick, confirmed by sampling the badge every
// 200ms across the whole window and never once seeing anything but
// "Connected". The fix below keeps this real (still a genuine WebSocket
// close, still MqttConnectionService's own exponential-backoff reconnect,
// task 1.3, running for real) while making the outage last long enough to
// reliably observe: the very first *reconnect attempt* is deliberately
// refused too (closed before `connectToServer()`), forcing a second,
// longer-backed-off attempt -- a real, multi-second gap instead of a
// sub-200ms one.
const VEHICLE_ID = 'e2e-vehicle-resync';
const INITIAL_LAT = 41.3874;
const INITIAL_LON = 2.1686;
const POST_RECONNECT_LAT = 41.3974;
const POST_RECONNECT_LON = 2.1786;

test.describe('Live map -- disconnect notice and resync on reconnect', () => {
  let publisher: MqttClient;

  test.beforeAll(async () => {
    publisher = await connectTestPublisher();
  });

  test.afterAll(async () => {
    await endTestPublisher(publisher);
  });

  test('losing the connection shows the disconnect notice; recovering resyncs the full snapshot+stream cycle', async ({
    page,
  }) => {
    let snapshotRequestCount = 0;
    let currentWsRoute: WebSocketRoute | undefined;
    // While false (the normal case), every connection attempt is a REAL
    // passthrough to the real broker. While true, a connection attempt is
    // refused immediately (closed before `connectToServer()`) -- simulating
    // the outage still being down for that specific retry, which is what
    // forces a second, longer-backed-off reconnect attempt below.
    let refuseNextConnection = false;
    await page.routeWebSocket(`${TEST_BROKER_WS_URL}/**`, (ws) => {
      currentWsRoute = ws;
      if (refuseNextConnection) {
        refuseNextConnection = false;
        ws.close();
        return;
      }
      ws.connectToServer();
    });

    await stubFleetApi(page, [
      {
        vehicleId: VEHICLE_ID,
        label: 'Resync Vehicle',
        lat: INITIAL_LAT,
        lon: INITIAL_LON,
        recordedAt: new Date().toISOString(),
        motionState: 'STOPPED',
        online: true,
      },
    ]);
    // Overrides stubFleetApi's own /api/fleet/state route (Playwright routes
    // apply most-recently-registered-first) purely to count calls; the
    // fulfilled response stays identical.
    await page.route('**/api/fleet/state', (route) => {
      snapshotRequestCount += 1;
      return route.fulfill({
        json: {
          vehicles: [
            {
              vehicleId: VEHICLE_ID,
              label: 'Resync Vehicle',
              lat: INITIAL_LAT,
              lon: INITIAL_LON,
              recordedAt: new Date().toISOString(),
              motionState: 'STOPPED',
              online: true,
            },
          ],
        },
      });
    });

    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toHaveText('Connected', { timeout: 15_000 });
    expect(snapshotRequestCount).toBe(1);

    refuseNextConnection = true;
    await currentWsRoute?.close();
    // The close only reaches MqttConnectionService.scheduleReconnect
    // asynchronously, and the status badge only flips once the first
    // reconnect attempt actually runs (~1s later, task 1.3's initial backoff
    // delay) -- not immediately on close. That first attempt is the one
    // `refuseNextConnection` forces to fail, so the badge stays visibly
    // non-"Connected" through the ~2s second-attempt backoff too, long
    // enough to reliably observe (a plain single close/reconnect completes
    // in well under 200ms in this local stub-everything setup -- too fast
    // for a Playwright poll tick to ever catch, confirmed empirically).
    await expect(page.getByTestId('connection-status')).not.toHaveText('Connected', { timeout: 10_000 });
    await expect(page.getByTestId('connection-status')).toHaveText(/Reconnecting|Disconnected/, { timeout: 10_000 });

    // No explicit "go back online" step needed: the SECOND reconnect
    // attempt is a real passthrough again (refuseNextConnection only
    // refuses once) -- MqttConnectionService's own backoff timer (task 1.3)
    // retries on its own, and `routeWebSocket`'s handler above passes that
    // fresh connection straight through to the real broker.
    await expect(page.getByTestId('connection-status')).toHaveText('Connected', { timeout: 20_000 });

    // Resync proof: the whole snapshot+stream cycle repeated (task 2.4), not
    // just the status badge recovering on its own.
    await expect.poll(() => snapshotRequestCount, { timeout: 5_000 }).toBeGreaterThan(1);

    // And the reconnected live stream still applies real messages afterward.
    await publishTelemetry(publisher, TEST_ORG_ID, VEHICLE_ID, {
      recordedAt: new Date().toISOString(),
      lat: POST_RECONNECT_LAT,
      lon: POST_RECONNECT_LON,
    });
    await page.getByRole('option').filter({ hasText: VEHICLE_ID }).click();
    await expect(page.getByTestId('vehicle-detail-position')).toContainText(String(POST_RECONNECT_LAT), {
      timeout: 10_000,
    });
  });
});
