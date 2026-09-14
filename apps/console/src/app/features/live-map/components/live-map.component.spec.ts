import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FleetStore } from '../services/fleet.store';
import { VehicleTrackService } from '../services/vehicle-track.service';
import { LiveMapComponent } from './live-map.component';

type Handler = (...args: unknown[]) => void;

// Fakes MapLibre's `Map` class so this spec never opens a real WebGL
// context or fetches real tiles -- it only proves LiveMapComponent calls
// MapLibre's imperative API correctly (addSource/addLayer/setData/click),
// the same style the mqtt.js mock (`mqtt-connection.service.spec.ts`) uses
// for a third-party client library.
const { fakeMaps, FakeMap } = vi.hoisted(() => {
  class FakeGeoJSONSource {
    readonly setData = vi.fn();
  }

  class FakeMapImpl {
    readonly addSourceCalls: Array<{ id: string; config: unknown }> = [];
    readonly addLayerCalls: Array<Record<string, unknown>> = [];
    readonly removeCalls: number[] = [];
    private readonly sources = new Map<string, FakeGeoJSONSource>();
    private readonly listeners = new Map<string, Handler[]>();
    private readonly layerListeners = new Map<string, Handler[]>();

    constructor(readonly options: Record<string, unknown>) {
      fakeMaps.push(this);
    }

    on(event: string, layerIdOrHandler: unknown, maybeHandler?: unknown): this {
      if (typeof maybeHandler === 'function') {
        const key = `${event}:${String(layerIdOrHandler)}`;
        const handlers = this.layerListeners.get(key) ?? [];
        handlers.push(maybeHandler as Handler);
        this.layerListeners.set(key, handlers);
      } else if (typeof layerIdOrHandler === 'function') {
        const handlers = this.listeners.get(event) ?? [];
        handlers.push(layerIdOrHandler as Handler);
        this.listeners.set(event, handlers);
      }
      return this;
    }

    fire(event: string): void {
      for (const handler of this.listeners.get(event) ?? []) {
        handler();
      }
    }

    fireLayerEvent(event: string, layerId: string, payload: unknown): void {
      for (const handler of this.layerListeners.get(`${event}:${layerId}`) ?? []) {
        handler(payload);
      }
    }

    hasImage(): boolean {
      return false;
    }

    // jsdom has no real canvas 2D context, so LiveMapComponent's
    // registerVehicleIcon() no-ops there; this fake never needs to record it.
    addImage(): void {
      /* no-op */
    }

    addSource(id: string, config: unknown): void {
      this.addSourceCalls.push({ id, config });
      this.sources.set(id, new FakeGeoJSONSource());
    }

    addLayer(config: Record<string, unknown>): void {
      this.addLayerCalls.push(config);
    }

    getSource(id: string): FakeGeoJSONSource | undefined {
      return this.sources.get(id);
    }

    remove(): void {
      this.removeCalls.push(1);
    }
  }

  const fakeMaps: FakeMapImpl[] = [];
  return { fakeMaps, FakeMap: FakeMapImpl };
});

vi.mock('maplibre-gl', () => ({ Map: FakeMap }));

@Component({
  selector: 'app-live-map-host',
  imports: [LiveMapComponent],
  template: `<app-live-map />`,
})
class HostComponent {}

async function createAndLoad(): Promise<{ fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>; map: InstanceType<typeof FakeMap> }> {
  const fixture = TestBed.createComponent(HostComponent);
  await fixture.whenStable();
  const map = fakeMaps[fakeMaps.length - 1];
  map.fire('load');
  return { fixture, map };
}

function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

describe('LiveMapComponent', () => {
  let store: InstanceType<typeof FleetStore>;

  beforeEach(() => {
    fakeMaps.length = 0;
    TestBed.configureTestingModule({
      providers: [{ provide: VehicleTrackService, useValue: { trackLine: () => undefined } }],
    });
    store = TestBed.inject(FleetStore);
  });

  // Task 4.1
  it('creates the map against a free, no-token tile style', async () => {
    const { map } = await createAndLoad();

    expect(map.options['style']).toBe('https://tiles.openfreemap.org/styles/liberty');
  });

  // Task 4.2: a GeoJSON-backed symbol layer, never a DOM marker element.
  it('adds a vehicles GeoJSON source and a symbol layer for markers', async () => {
    const { map } = await createAndLoad();

    expect(map.addSourceCalls.some((call) => call.id === 'vehicles')).toBe(true);
    const vehicleLayer = map.addLayerCalls.find((layer) => layer['id'] === 'vehicles-layer');
    expect(vehicleLayer).toMatchObject({ type: 'symbol', source: 'vehicles' });
  });

  // Task 4.3
  it('styles the vehicle layer by heading, motion state and offline/stale dimming', async () => {
    const { map } = await createAndLoad();

    const vehicleLayer = map.addLayerCalls.find((layer) => layer['id'] === 'vehicles-layer') as {
      layout: Record<string, unknown>;
      paint: Record<string, unknown>;
    };
    expect(vehicleLayer.layout['icon-rotate']).toEqual(['get', 'heading']);
    expect(JSON.stringify(vehicleLayer.paint['icon-color'])).toContain('motionState');
    expect(JSON.stringify(vehicleLayer.paint['icon-opacity'])).toContain('stale');
  });

  // Task 4.6
  it('adds a line layer for the selected vehicle historical track', async () => {
    const { map } = await createAndLoad();

    const trackLayer = map.addLayerCalls.find((layer) => layer['id'] === 'selected-vehicle-track-layer');
    expect(trackLayer).toMatchObject({ type: 'line', source: 'selected-vehicle-track' });
  });

  it('feeds FleetStore vehicles into the vehicles source through the animation loop', async () => {
    const { map } = await createAndLoad();

    store.applySnapshot({ vehicles: [{ vehicleId: 'v1', lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' }] });
    TestBed.tick();
    await nextFrame();
    await nextFrame();

    const source = map.getSource('vehicles');
    expect(source?.setData).toHaveBeenCalled();
    const calls = source?.setData.mock.calls ?? [];
    const lastCall = calls[calls.length - 1]?.[0] as { features: Array<{ properties: { vehicleId: string } }> };
    expect(lastCall.features.some((feature) => feature.properties.vehicleId === 'v1')).toBe(true);
  });

  // Resolved gap (documented in tasks.md): before WU5's side panel exists,
  // clicking a marker is the only producer of FleetStore.selectVehicle(),
  // which the track httpResource (task 3.3) depends on.
  it('selects a vehicle in FleetStore when its marker is clicked', async () => {
    const { map } = await createAndLoad();

    map.fireLayerEvent('click', 'vehicles-layer', { features: [{ properties: { vehicleId: 'v1' } }] });

    expect(store.selectedVehicleId()).toBe('v1');
  });

  it('removes the map instance on destroy', async () => {
    const { fixture, map } = await createAndLoad();

    fixture.destroy();

    expect(map.removeCalls.length).toBe(1);
  });
});
