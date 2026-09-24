import type { ComponentFixture } from '@angular/core/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject } from 'rxjs';
import type { GeofenceResponse } from '@fleetpulse/api-client';
import { GeolocationService, type GeolocationPoint } from '../../../core/geolocation/geolocation.service';
import type { GeofenceDraft } from '../models/geofence-draft.model';
import { GeofenceDrawingEditorComponent } from './geofence-drawing-editor.component';

type Handler = (payload?: unknown) => void;

// Same deterministic stand-in for GeolocationService.position() LiveMapComponent's
// own spec uses: a real, controllable async source (never emits synchronously).
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

// Same fake-`maplibre-gl` recipe LiveMapComponent's own spec established
// (04-add-live-map): never opens a real WebGL context, only proves this
// component calls MapLibre's imperative API correctly.
const { fakeMaps, FakeMap } = vi.hoisted(() => {
  class FakeGeoJSONSource {
    readonly setData = vi.fn();
  }

  class FakeMapImpl {
    readonly addSourceCalls: Array<{ id: string; config: unknown }> = [];
    readonly addLayerCalls: Array<Record<string, unknown>> = [];
    readonly removeCalls: number[] = [];
    readonly easeToCalls: Array<Record<string, unknown>> = [];
    readonly doubleClickZoom = { disable: vi.fn() };
    private readonly sources = new Map<string, FakeGeoJSONSource>();
    private readonly listeners = new Map<string, Handler[]>();

    constructor(readonly options: Record<string, unknown>) {
      fakeMaps.push(this);
    }

    on(event: string, handler: Handler): this {
      const handlers = this.listeners.get(event) ?? [];
      handlers.push(handler);
      this.listeners.set(event, handlers);
      return this;
    }

    fire(event: string, payload?: unknown): void {
      for (const handler of this.listeners.get(event) ?? []) {
        handler(payload);
      }
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

    easeTo(options: Record<string, unknown>): void {
      this.easeToCalls.push(options);
    }
  }

  const fakeMaps: FakeMapImpl[] = [];
  return { fakeMaps, FakeMap: FakeMapImpl };
});

vi.mock('maplibre-gl', () => ({ Map: FakeMap }));

function clickButton(fixture: ComponentFixture<GeofenceDrawingEditorComponent>, testId: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${testId}"]`))?.triggerEventHandler('onClick', undefined);
}

function mapClick(map: InstanceType<typeof FakeMap>, lat: number, lon: number): void {
  map.fire('click', { lngLat: { lat, lng: lon } });
}

function mapMouseMove(map: InstanceType<typeof FakeMap>, lat: number, lon: number): void {
  map.fire('mousemove', { lngLat: { lat, lng: lon } });
}

async function createAndLoad(): Promise<{ fixture: ComponentFixture<GeofenceDrawingEditorComponent>; map: InstanceType<typeof FakeMap> }> {
  const fixture = TestBed.createComponent(GeofenceDrawingEditorComponent);
  await fixture.whenStable();
  const map = fakeMaps[fakeMaps.length - 1];
  map.fire('load');
  await fixture.whenStable();
  return { fixture, map };
}

// Same "writes one of its own read dependencies" convention as
// LiveMapComponent's own spec -- the geolocation-centering effect sets
// `initialCenterApplied`, which it also reads, so a signal write it reacts
// to takes two flush passes to fully settle.
function flushCenteringEffect(): void {
  TestBed.tick();
  TestBed.tick();
}

describe('GeofenceDrawingEditorComponent', () => {
  let geolocationService: FakeGeolocationService;

  beforeEach(() => {
    fakeMaps.length = 0;
    geolocationService = new FakeGeolocationService();
    TestBed.configureTestingModule({
      providers: [{ provide: GeolocationService, useValue: geolocationService }],
    });
  });

  it('creates the map against the same free, no-token tile style as the live map', async () => {
    const { map } = await createAndLoad();
    expect(map.options['style']).toBe('https://tiles.openfreemap.org/styles/liberty');
  });

  // Task 5.1
  it('adds context, draft-shape and draft-vertices GeoJSON sources, and disables double-click zoom', async () => {
    const { map } = await createAndLoad();

    expect(map.addSourceCalls.map((c) => c.id)).toEqual(
      expect.arrayContaining(['geofence-context', 'geofence-draft', 'geofence-draft-vertices']),
    );
    expect(map.doubleClickZoom.disable).toHaveBeenCalledTimes(1);
  });

  it('draws a polygon from 3 map clicks and emits a POLYGON draft once Finish is clicked', async () => {
    const { fixture, map } = await createAndLoad();
    let emitted: GeofenceDraft | undefined;
    fixture.componentInstance.draftChange.subscribe((draft) => (emitted = draft));

    clickButton(fixture, 'draw-polygon');
    await fixture.whenStable();

    mapClick(map, 1, 1);
    mapClick(map, 2, 2);
    mapClick(map, 3, 1);
    await fixture.whenStable();

    const finishButton = fixture.debugElement.query(By.css('[data-testid="finish-polygon"]')).componentInstance as {
      disabled: boolean;
    };
    expect(finishButton.disabled).toBe(false);

    clickButton(fixture, 'finish-polygon');
    await fixture.whenStable();

    expect(emitted).toEqual({
      shape: 'POLYGON',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    });
  });

  it('keeps Finish shape disabled with fewer than 3 vertices', async () => {
    const { fixture, map } = await createAndLoad();

    clickButton(fixture, 'draw-polygon');
    await fixture.whenStable();
    mapClick(map, 1, 1);
    mapClick(map, 2, 2);
    await fixture.whenStable();

    const finishButton = fixture.debugElement.query(By.css('[data-testid="finish-polygon"]')).componentInstance as {
      disabled: boolean;
    };
    expect(finishButton.disabled).toBe(true);
  });

  // Task 5.1: circle drawn as center click + edge click, matching
  // GeofenceRequest's center/radiusMeters shape -- never vertices.
  it('draws a circle from a center click plus an edge click, committing on the second click', async () => {
    const { fixture, map } = await createAndLoad();
    let emitted: GeofenceDraft | undefined;
    fixture.componentInstance.draftChange.subscribe((draft) => (emitted = draft));

    clickButton(fixture, 'draw-circle');
    await fixture.whenStable();

    mapClick(map, 0, 0);
    await fixture.whenStable();
    mapMouseMove(map, 0, 0.01);
    await fixture.whenStable();
    mapClick(map, 0, 0.01);
    await fixture.whenStable();

    expect(emitted?.shape).toBe('CIRCLE');
    if (emitted?.shape === 'CIRCLE') {
      expect(emitted.center).toEqual({ lat: 0, lon: 0 });
      expect(emitted.radiusMeters).toBeGreaterThan(0);
    }
  });

  it('cancelDrawing clears an in-progress polygon without ever emitting a draft', async () => {
    const { fixture, map } = await createAndLoad();
    let emitted: GeofenceDraft | undefined;
    fixture.componentInstance.draftChange.subscribe((draft) => (emitted = draft));

    clickButton(fixture, 'draw-polygon');
    await fixture.whenStable();
    mapClick(map, 1, 1);
    mapClick(map, 2, 2);
    mapClick(map, 3, 1);
    await fixture.whenStable();

    clickButton(fixture, 'cancel-drawing');
    await fixture.whenStable();

    expect(emitted).toBeUndefined();
  });

  // Task 5.2 (console half): the container increments resetToken after a
  // successful save or an explicit "New geofence" action.
  it('clears a committed draft when resetToken changes', async () => {
    const { fixture, map } = await createAndLoad();
    let emitted: GeofenceDraft | undefined;
    fixture.componentInstance.draftChange.subscribe((draft) => (emitted = draft));

    clickButton(fixture, 'draw-polygon');
    await fixture.whenStable();
    mapClick(map, 1, 1);
    mapClick(map, 2, 2);
    mapClick(map, 3, 1);
    await fixture.whenStable();
    clickButton(fixture, 'finish-polygon');
    await fixture.whenStable();
    expect(emitted).toBeDefined();

    fixture.componentRef.setInput('resetToken', 1);
    await fixture.whenStable();

    expect(emitted).toBeUndefined();
  });

  // Task 5.3 (editing context, shared with the live map): existing
  // geofences render through the exact same toGeofenceFeatureCollection()
  // LiveMapComponent uses.
  it('renders existingGeofences into the context source whenever the input changes', async () => {
    const { fixture, map } = await createAndLoad();
    const geofences: GeofenceResponse[] = [
      { id: 'g1', name: 'Depot', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }, { lat: 1, lon: 1 }] },
    ];

    fixture.componentRef.setInput('existingGeofences', geofences);
    await fixture.whenStable();

    const source = map.getSource('geofence-context');
    expect(source?.setData).toHaveBeenCalled();
    const calls = source?.setData.mock.calls ?? [];
    const lastCall = calls[calls.length - 1]?.[0] as { features: unknown[] };
    expect(lastCall.features).toHaveLength(1);
  });

  it('removes the map instance on destroy', async () => {
    const { fixture, map } = await createAndLoad();

    fixture.destroy();

    expect(map.removeCalls.length).toBe(1);
  });

  // User decision (2026-09-24): geolocation centering, same convention as
  // LiveMapComponent's own spec -- map creation never waits on it.
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

    it('keeps the default view when geolocation is denied/unavailable', async () => {
      const { map } = await createAndLoad();

      geolocationService.resolve(undefined);
      flushCenteringEffect();

      expect(map.easeToCalls.length).toBe(0);
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
  });
});
