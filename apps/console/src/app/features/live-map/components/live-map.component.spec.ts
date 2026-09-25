import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { GeolocationService, type GeolocationPoint } from '../../../core/geolocation/geolocation.service';
import { FleetStore } from '../services/fleet.store';
import { VehicleTrackService } from '../services/vehicle-track.service';
import type { TrackSegmentFeature } from '../services/vehicle-track.util';
import { GeofenceService } from '../../geofencing/services/geofence.service';
import { GeofenceStore } from '../../geofencing/services/geofence.store';
import { LiveMapComponent } from './live-map.component';

// Real signal (not a plain function) so a test can push a new value after the
// component has already read it once and prove the render effect re-runs --
// the same reason FakeGeolocationService above is a real async source rather
// than a stubbed return value.
class FakeVehicleTrackService {
  private readonly features = signal<TrackSegmentFeature[]>([]);
  readonly trackFeatures = this.features.asReadonly();

  setFeatures(features: TrackSegmentFeature[]): void {
    this.features.set(features);
  }
}

// Deterministic stand-in for GeolocationService.position(): a real,
// controllable async source (never emits synchronously), so tests can
// assert the map is created/loaded before it resolves and can drive
// granted/denied/pending outcomes explicitly.
class FakeGeolocationService {
  private readonly subject = new Subject<GeolocationPoint | undefined>();

  position() {
    return this.subject.asObservable();
  }

  resolve(point: GeolocationPoint | undefined): void {
    this.subject.next(point);
    this.subject.complete();
  }
}

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
    readonly flyToCalls: Array<Record<string, unknown>> = [];
    readonly easeToCalls: Array<Record<string, unknown>> = [];
    readonly fitBoundsCalls: Array<[unknown, unknown]> = [];
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

    fire(event: string, payload?: unknown): void {
      for (const handler of this.listeners.get(event) ?? []) {
        handler(payload);
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

    flyTo(options: Record<string, unknown>): void {
      this.flyToCalls.push(options);
    }

    easeTo(options: Record<string, unknown>): void {
      this.easeToCalls.push(options);
    }

    fitBounds(bounds: unknown, options: unknown): void {
      this.fitBoundsCalls.push([bounds, options]);
    }

    getZoom(): number {
      return 2;
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

// The geolocation-centering effect writes `initialCenterApplied` -- one of
// its own read dependencies -- so a signal write it reacts to (geolocation
// resolving, a drag/zoom, a vehicle selection) takes two flush passes to
// settle: one to run the branch and write that signal, one more so the
// now-dirty effect re-runs and observes its own write. A single
// `TestBed.tick()` only guarantees the first pass.
function flushCenteringEffect(): void {
  TestBed.tick();
  TestBed.tick();
}

describe('LiveMapComponent', () => {
  let store: InstanceType<typeof FleetStore>;
  let geofenceStore: InstanceType<typeof GeofenceStore>;
  let geolocationService: FakeGeolocationService;
  let vehicleTrackService: FakeVehicleTrackService;

  beforeEach(() => {
    fakeMaps.length = 0;
    geolocationService = new FakeGeolocationService();
    vehicleTrackService = new FakeVehicleTrackService();
    TestBed.configureTestingModule({
      providers: [
        { provide: VehicleTrackService, useValue: vehicleTrackService },
        // Task 5.3: GeofenceService does real HTTP (via getJson()), which
        // has no backend to hit here -- faked the same way VehicleTrackService
        // is above, while GeofenceStore (the pure state it feeds) stays real
        // so renderGeofences() can be proven directly via store.setGeofences().
        { provide: GeofenceService, useValue: { load: vi.fn() } },
        { provide: GeolocationService, useValue: geolocationService },
      ],
    });
    store = TestBed.inject(FleetStore);
    geofenceStore = TestBed.inject(GeofenceStore);
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

  // Prod QA (2026-09-24): the track used to reuse a blue close enough to the
  // geofence layer to blend into it.
  it('colors the track line distinctly from vehicle markers and geofence layers', async () => {
    const { map } = await createAndLoad();

    const trackLayer = map.addLayerCalls.find((layer) => layer['id'] === 'selected-vehicle-track-layer') as {
      paint: Record<string, unknown>;
    };
    const geofenceLayer = map.addLayerCalls.find((layer) => layer['id'] === 'geofences-layer') as {
      paint: Record<string, unknown>;
    };
    const geofenceOutlineLayer = map.addLayerCalls.find((layer) => layer['id'] === 'geofences-outline-layer') as {
      paint: Record<string, unknown>;
    };
    const vehicleLayer = map.addLayerCalls.find((layer) => layer['id'] === 'vehicles-layer') as {
      paint: Record<string, unknown>;
    };

    const trackColor = trackLayer.paint['line-color'];
    expect(trackColor).not.toBe(geofenceLayer.paint['fill-color']);
    expect(trackColor).not.toBe(geofenceOutlineLayer.paint['line-color']);
    expect(JSON.stringify(vehicleLayer.paint['icon-color'])).not.toContain(trackColor as string);
  });

  // Prod QA (2026-09-24): an implausible jump (simulator teleport, GPS gap)
  // must never draw as a single straight line -- VehicleTrackService already
  // splits it into separate segment features (vehicle-track.util.spec.ts),
  // this only proves each one reaches the map source untouched.
  it('renders every track segment from VehicleTrackService as its own feature on the track source', async () => {
    const { map } = await createAndLoad();
    const segments: TrackSegmentFeature[] = [
      { type: 'Feature', geometry: { type: 'LineString', coordinates: [[1, 1], [2, 2]] }, properties: {} },
      { type: 'Feature', geometry: { type: 'LineString', coordinates: [[9, 9], [10, 10]] }, properties: {} },
    ];
    vehicleTrackService.setFeatures(segments);
    TestBed.tick();

    const source = map.getSource('selected-vehicle-track');
    const calls = source?.setData.mock.calls ?? [];
    const lastCall = calls[calls.length - 1]?.[0] as { features: unknown[] };
    expect(lastCall.features).toEqual(segments);
  });

  // Task 5.3
  it('adds a fill+outline geofences layer and renders GeofenceStore active geofences into it', async () => {
    const { map } = await createAndLoad();

    expect(map.addLayerCalls.find((layer) => layer['id'] === 'geofences-layer')).toMatchObject({
      type: 'fill',
      source: 'geofences',
    });
    expect(map.addLayerCalls.find((layer) => layer['id'] === 'geofences-outline-layer')).toMatchObject({
      type: 'line',
      source: 'geofences',
    });

    geofenceStore.setGeofences([
      { id: 'g1', name: 'Depot', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }, { lat: 1, lon: 1 }] },
    ]);
    TestBed.tick();

    const source = map.getSource('geofences');
    expect(source?.setData).toHaveBeenCalled();
    const calls = source?.setData.mock.calls ?? [];
    const lastCall = calls[calls.length - 1]?.[0] as { features: Array<{ properties: { id: string } }> };
    expect(lastCall.features.some((feature) => feature.properties.id === 'g1')).toBe(true);
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

  // Task 5.2: map -> highlight. The selected vehicle's feature carries
  // `selected: true`, which the layer's `icon-size` expression (task 5.2)
  // reads to render it larger.
  it('marks the selected vehicle as selected in the vehicles source data', async () => {
    const { map } = await createAndLoad();
    store.applySnapshot({
      vehicles: [
        { vehicleId: 'v1', lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' },
        { vehicleId: 'v2', lat: 3, lon: 4, recordedAt: '2026-01-01T00:00:00Z' },
      ],
    });
    store.selectVehicle('v2');
    TestBed.tick();
    await nextFrame();
    await nextFrame();

    const source = map.getSource('vehicles');
    const calls = source?.setData.mock.calls ?? [];
    const lastCall = calls[calls.length - 1]?.[0] as {
      features: Array<{ properties: { vehicleId: string; selected: boolean } }>;
    };
    const byId = new Map(lastCall.features.map((feature) => [feature.properties.vehicleId, feature.properties.selected]));
    expect(byId.get('v1')).toBe(false);
    expect(byId.get('v2')).toBe(true);
  });

  it('declares icon-size as a data-driven expression keyed on the selected property', async () => {
    const { map } = await createAndLoad();

    const vehicleLayer = map.addLayerCalls.find((layer) => layer['id'] === 'vehicles-layer') as {
      layout: Record<string, unknown>;
    };
    expect(JSON.stringify(vehicleLayer.layout['icon-size'])).toContain('selected');
  });

  // Task 5.2: list -> map centering. Reads the vehicle's real reported
  // lat/lon from FleetStore, never an interpolated value.
  it('flies to the selected vehicle real reported position once the style has loaded', async () => {
    const { map } = await createAndLoad();
    store.applySnapshot({ vehicles: [{ vehicleId: 'v1', lat: 12, lon: 34, recordedAt: '2026-01-01T00:00:00Z' }] });
    TestBed.tick();

    store.selectVehicle('v1');
    TestBed.tick();

    expect(map.flyToCalls.length).toBe(1);
    expect(map.flyToCalls[0]?.['center']).toEqual([34, 12]);
  });

  it('does not fly to an unknown selected vehicle', async () => {
    const { map } = await createAndLoad();

    store.selectVehicle('missing');
    TestBed.tick();

    expect(map.flyToCalls.length).toBe(0);
  });

  // User decision (2026-09-24): geolocation centering. Map creation itself
  // must never wait on it -- createAndLoad() below never resolves
  // geolocationService before asserting the map already exists at the
  // fallback view.
  describe('geolocation centering', () => {
    it('creates and loads the map at the default view without waiting on geolocation', async () => {
      const { map } = await createAndLoad();

      expect(map.options['center']).toEqual([0, 0]);
      expect(map.options['zoom']).toBe(2);
      expect(map.easeToCalls.length).toBe(0);
    });

    it('centers on the resolved user position via easeTo at ~zoom 12', async () => {
      const { map } = await createAndLoad();

      geolocationService.resolve({ lat: 10, lon: 20 });
      flushCenteringEffect();

      expect(map.easeToCalls).toEqual([{ center: [20, 10], zoom: 12 }]);
    });

    it('fits the fleet bounds when geolocation is denied/unavailable and vehicle positions are known', async () => {
      const { map } = await createAndLoad();
      store.applySnapshot({
        vehicles: [
          { vehicleId: 'v1', lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' },
          { vehicleId: 'v2', lat: 30, lon: 5, recordedAt: '2026-01-01T00:00:00Z' },
        ],
      });
      TestBed.tick();

      geolocationService.resolve(undefined);
      flushCenteringEffect();

      expect(map.fitBoundsCalls.length).toBe(1);
      expect(map.fitBoundsCalls[0]?.[0]).toEqual([
        [5, 10],
        [20, 30],
      ]);
      expect(map.easeToCalls.length).toBe(0);
    });

    it('keeps the default view when geolocation is denied/unavailable and no vehicle positions are known', async () => {
      const { map } = await createAndLoad();

      geolocationService.resolve(undefined);
      flushCenteringEffect();

      expect(map.easeToCalls.length).toBe(0);
      expect(map.fitBoundsCalls.length).toBe(0);
    });

    // Bugfix (2026-09-25 prod QA): the world-default view used to stick
    // forever once geolocation settled with no point and no vehicles were
    // known yet, because `initialCenterApplied` was marked true right away.
    // It must now keep waiting and fit the fleet bounds once a position
    // eventually arrives.
    it('fits the fleet bounds once when vehicle positions arrive after a denied/unavailable geolocation', async () => {
      const { map } = await createAndLoad();

      geolocationService.resolve(undefined);
      flushCenteringEffect();
      expect(map.fitBoundsCalls.length).toBe(0);

      store.applySnapshot({
        vehicles: [{ vehicleId: 'v1', lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' }],
      });
      TestBed.tick();

      expect(map.fitBoundsCalls.length).toBe(1);
      expect(map.fitBoundsCalls[0]?.[0]).toEqual([
        [20, 10],
        [20, 10],
      ]);

      store.applySnapshot({
        vehicles: [
          { vehicleId: 'v1', lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' },
          { vehicleId: 'v2', lat: 30, lon: 40, recordedAt: '2026-01-01T00:00:00Z' },
        ],
      });
      TestBed.tick();

      expect(map.fitBoundsCalls.length).toBe(1);
    });

    it('does not fit the fleet bounds if the user drags before any vehicle position arrives', async () => {
      const { map } = await createAndLoad();

      geolocationService.resolve(undefined);
      flushCenteringEffect();

      map.fire('dragstart', { originalEvent: {} });
      flushCenteringEffect();

      store.applySnapshot({
        vehicles: [{ vehicleId: 'v1', lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' }],
      });
      TestBed.tick();

      expect(map.fitBoundsCalls.length).toBe(0);
    });

    it('does not center on the resolved position once the user has dragged the map', async () => {
      const { map } = await createAndLoad();

      map.fire('dragstart', { originalEvent: {} });
      geolocationService.resolve({ lat: 10, lon: 20 });
      flushCenteringEffect();

      expect(map.easeToCalls.length).toBe(0);
    });

    it('does not treat a programmatic zoomstart (no originalEvent) as user interaction', async () => {
      const { map } = await createAndLoad();

      map.fire('zoomstart', {});
      geolocationService.resolve({ lat: 10, lon: 20 });
      flushCenteringEffect();

      expect(map.easeToCalls).toEqual([{ center: [20, 10], zoom: 12 }]);
    });

    it('does not center on the resolved position once a vehicle has already been selected', async () => {
      const { map } = await createAndLoad();

      store.selectVehicle('some-vehicle');
      TestBed.tick();
      geolocationService.resolve({ lat: 10, lon: 20 });
      flushCenteringEffect();

      expect(map.easeToCalls.length).toBe(0);
    });
  });
});
