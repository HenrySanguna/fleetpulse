import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map as mapOperator } from 'rxjs';
import { Map as MapLibreMap } from 'maplibre-gl';
import type { FeatureCollection, LineString } from 'geojson';
import type { GeoJSONSource, MapLayerMouseEvent } from 'maplibre-gl';
import type { GeofenceResponse } from '@fleetpulse/api-client';
import { GeolocationService, type GeolocationPoint } from '../../../core/geolocation/geolocation.service';
import { FleetStore } from '../services/fleet.store';
import { VehicleTrackService } from '../services/vehicle-track.service';
import { VehicleInterpolationEngine } from '../services/vehicle-interpolation';
import { toVehicleFeatureCollection } from '../services/vehicle-symbol.util';
import type { VehicleState } from '../models/vehicle-state.model';
import type { TrackSegmentFeature } from '../services/vehicle-track.util';
import { toGeofenceFeatureCollection } from '../../geofencing/services/geofence-geometry.util';
import { GeofenceService } from '../../geofencing/services/geofence.service';
import { GeofenceStore } from '../../geofencing/services/geofence.store';

// Task 4.1: free tile provider, no paid token (project.md forbids Mapbox GL
// JS specifically, for exactly that reason). OpenFreeMap
// (https://openfreemap.org) serves a complete MapLibre style + vector tiles
// with no API key, no request quota and no signup -- unlike MapTiler's or
// Stadia's free *tiers*, it never requires registering a token at all, which
// is the literal "sin token de pago" constraint, not just "free within a
// quota". It is also self-hostable later if that project ever needs to.
const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

const VEHICLE_ICON_ID = 'vehicle-arrow';
const VEHICLES_SOURCE_ID = 'vehicles';
const VEHICLES_LAYER_ID = 'vehicles-layer';
const TRACK_SOURCE_ID = 'selected-vehicle-track';
const TRACK_LAYER_ID = 'selected-vehicle-track-layer';
const GEOFENCES_SOURCE_ID = 'geofences';
const GEOFENCES_LAYER_ID = 'geofences-layer';
const GEOFENCES_OUTLINE_LAYER_ID = 'geofences-outline-layer';

// Prod QA (2026-09-24): distinct from every vehicle motion-state color
// (#22c55e/#eab308/#6b7280) and the geofence fill/outline (#2563eb) -- the
// track used to reuse a blue close enough to the geofence layer to blend
// into it. `live-map-page.component.css`'s `.map-legend-dot-track` mirrors
// this literal value, same convention as the geofence/moving/idling dots.
const TRACK_LINE_COLOR = '#a855f7';

// Fallback view used both at map creation (so creation never waits on the
// geolocation permission prompt) and if geolocation ends up denied/
// unavailable/timed out with no known vehicle position to fit instead.
const DEFAULT_CENTER: [number, number] = [0, 0];
const DEFAULT_ZOOM = 2;
const USER_LOCATION_ZOOM = 12;

interface GeolocationOutcome {
  readonly settled: boolean;
  readonly point: GeolocationPoint | undefined;
}

const PENDING_GEOLOCATION: GeolocationOutcome = { settled: false, point: undefined };

const EMPTY_TRACK_COLLECTION: FeatureCollection<LineString, Record<string, never>> = {
  type: 'FeatureCollection',
  features: [],
};

// Test 6.5's E2E hook only. `isDevMode()` was tried first but Angular's
// build-mode stripping isn't the relevant axis here -- apps/console-e2e
// targets the production bundle specifically (its own dev-server run hits
// an unrelated Vite dependency-prebundling gap for maplibre-gl's worker
// chunk, `maplibre-gl-worker.mjs` 404s under `ng serve`, tracked separately
// from this WU's scope), so a build-mode flag would never be true where the
// E2E suite actually runs. `apps/console-e2e/src/support/live-map-stubs.ts`
// sets this via `page.addInitScript()` BEFORE navigation, so it is present
// only for an explicit, opted-in E2E run and never for a real deployment,
// regardless of build configuration -- MapLibre's own public
// `queryRenderedFeatures` API is what the E2E test reads through this
// reference, not any private internal.
function isE2eHarness(): boolean {
  return (window as unknown as { __fleetpulseE2E?: boolean }).__fleetpulseE2E === true;
}

// Tasks 4.1-4.6: MapLibre GL wiring. Deliberately thin -- every non-trivial
// decision (interpolation math, GeoJSON/styling-property derivation, track
// line shape) lives in `services/`, this component only calls MapLibre's
// own imperative API with their output. Vehicles render through a `symbol`
// layer backed by a GeoJSON source, never DOM marker elements (task 4.2):
// hundreds of DOM nodes updated on every frame is the exact perf problem
// design.md calls out, a single `source.setData()` per frame is not.
@Component({
  selector: 'app-live-map',
  templateUrl: './live-map.component.html',
  styleUrl: './live-map.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveMapComponent implements AfterViewInit, OnDestroy {
  private readonly mapContainer = viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');
  private readonly fleetStore = inject(FleetStore);
  private readonly trackService = inject(VehicleTrackService);
  private readonly geofenceService = inject(GeofenceService);
  private readonly geofenceStore = inject(GeofenceStore);
  private readonly geolocationService = inject(GeolocationService);

  private readonly interpolation = new VehicleInterpolationEngine();
  private latestVehicles: readonly VehicleState[] = [];
  private map: MapLibreMap | undefined;
  private frameId: number | undefined;
  // A signal (not a plain field) specifically so the task 5.2 centering
  // effect below re-runs once the map finishes loading, even if a selection
  // already happened before that -- effects only re-run when a *signal*
  // dependency changes, not a plain class field mutation.
  private readonly styleLoaded = signal(false);
  // Set from the map's own 'dragstart'/'zoomstart' events, but only when
  // they carry an `originalEvent` -- that's what distinguishes a real user
  // gesture from a programmatic move (jumpTo/easeTo/flyTo never set it).
  private readonly userInteracted = signal(false);
  // Geolocation resolves asynchronously and the map must never wait on it
  // (map creation below always uses DEFAULT_CENTER/DEFAULT_ZOOM first) --
  // `settled` distinguishes "still waiting on the browser prompt" from
  // "resolved to no position" (denied/unavailable/timed out), which toSignal's
  // own `initialValue` alone can't express since both cases carry `point: undefined`.
  private readonly geolocationOutcome = toSignal(
    this.geolocationService.position().pipe(mapOperator((point): GeolocationOutcome => ({ settled: true, point }))),
    { initialValue: PENDING_GEOLOCATION },
  );
  // Guards the one-time initial centering below so it never re-fires once
  // it has already deferred to a user interaction/vehicle selection, or
  // already applied the resolved geolocation/fleet-bounds/default view.
  private readonly initialCenterApplied = signal(false);

  constructor() {
    // Task 4.4: every time FleetStore's real (never interpolated) reported
    // position changes for a vehicle, hand it to the interpolation engine so
    // it animates towards it instead of snapping. Actual `setData()` calls
    // happen once per animation frame in `animationLoop`, not here.
    effect(() => {
      const vehicles = this.fleetStore.visibleVehicles();
      this.latestVehicles = vehicles;
      for (const vehicle of vehicles) {
        if (typeof vehicle.lat === 'number' && typeof vehicle.lon === 'number') {
          this.interpolation.recordPosition(vehicle.vehicleId, { lat: vehicle.lat, lon: vehicle.lon });
        } else {
          this.interpolation.forget(vehicle.vehicleId);
        }
      }
    });

    // Task 4.6. Independent of the vehicle layer: requirement "Independencia
    // del mapa en vivo respecto al servicio HTTP" means this resource
    // failing must never affect the live vehicle rendering above.
    effect(() => {
      this.renderTrack(this.trackService.trackFeatures());
    });

    // Task 5.2: list -> map. Centers on whichever vehicle FleetStore.
    // selectedVehicleId() points at -- fired by either the side panel (WU5)
    // or this component's own marker click handler below (WU4), since both
    // write to the same signal. Reads the vehicle's real reported lat/lon
    // (never the interpolated visual sample) -- centering only needs to be
    // approximately right, and this keeps the map's one MapLibre-specific
    // side effect independent of the animation loop's per-frame state.
    effect(() => {
      const vehicleId = this.fleetStore.selectedVehicleId();
      const map = this.map;
      if (!vehicleId || !map || !this.styleLoaded()) {
        return;
      }
      const vehicle = this.fleetStore.vehicles().get(vehicleId);
      if (typeof vehicle?.lat === 'number' && typeof vehicle.lon === 'number') {
        map.flyTo({ center: [vehicle.lon, vehicle.lat], zoom: Math.max(map.getZoom(), 14), essential: true });
      }
    });

    // Task 5.3: active-geofence visualization. Independent of the vehicle
    // layer and the track resource above, same "one resource's failure
    // never affects another layer" convention -- GeofenceStore is shared
    // with the geofencing editor (WU7's own page), so both read the exact
    // same fetched list.
    effect(() => {
      this.renderGeofences(this.geofenceStore.geofences());
    });

    // User decision (2026-09-24): center the live map on the dispatcher's
    // own location once geolocation resolves, unless they already interacted
    // with the map (drag/zoom) or selected a vehicle -- either one means the
    // dispatcher has already chosen what to look at, so a late-resolving
    // geolocation prompt must not yank the view out from under them. Never
    // blocks map creation (ngAfterViewInit below always creates the map at
    // DEFAULT_CENTER/DEFAULT_ZOOM first).
    effect(() => {
      const map = this.map;
      // Read unconditionally, before any early return: an effect only
      // reacts to a signal it actually read on its LAST run, so reading this
      // behind the guards below would miss a geolocation resolution that
      // lands before `styleLoaded`/`map` are ready (the earlier runs would
      // never have subscribed to it, and a later run reads a value that's
      // simply never invalidated again).
      const outcome = this.geolocationOutcome();
      if (!map || !this.styleLoaded() || this.initialCenterApplied()) {
        return;
      }
      if (this.userInteracted() || this.fleetStore.selectedVehicleId()) {
        this.initialCenterApplied.set(true);
        return;
      }
      if (!outcome.settled) {
        return;
      }
      if (outcome.point) {
        this.initialCenterApplied.set(true);
        map.easeTo({ center: [outcome.point.lon, outcome.point.lat], zoom: USER_LOCATION_ZOOM });
        return;
      }
      // Settled with no point (denied/unavailable/timed out): only mark the
      // initial centering as applied once there was actually something to
      // fit to. With no known vehicles yet, leave it unmarked so this effect
      // keeps re-running (it reads fleetStore.vehicles() inside
      // centerOnFleetOrDefault) until a fleet position arrives, instead of
      // being stuck at the world-default view forever.
      if (this.centerOnFleetOrDefault(map)) {
        this.initialCenterApplied.set(true);
      }
    });
  }

  ngAfterViewInit(): void {
    const map = new MapLibreMap({
      container: this.mapContainer().nativeElement,
      style: MAP_STYLE_URL,
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
    });
    this.map = map;

    // Only a real user gesture carries `originalEvent` -- a programmatic
    // easeTo/flyTo/fitBounds (this component's own centering) never does.
    map.on('dragstart', (event) => {
      if (event.originalEvent) {
        this.userInteracted.set(true);
      }
    });
    map.on('zoomstart', (event) => {
      if (event.originalEvent) {
        this.userInteracted.set(true);
      }
    });

    map.on('load', () => {
      this.registerVehicleIcon(map);
      this.addVehicleLayer(map);
      this.addTrackLayer(map);
      this.addGeofenceLayer(map);
      this.styleLoaded.set(true);

      if (isE2eHarness()) {
        (window as unknown as { __fleetpulseLiveMap?: MapLibreMap }).__fleetpulseLiveMap = map;
      }

      map.on('click', VEHICLES_LAYER_ID, (event: MapLayerMouseEvent) => {
        const vehicleId = event.features?.[0]?.properties?.['vehicleId'];
        if (typeof vehicleId === 'string') {
          this.fleetStore.selectVehicle(vehicleId);
        }
      });

      this.renderTrack(this.trackService.trackFeatures());
      this.renderGeofences(this.geofenceStore.geofences());
    });

    this.frameId = requestAnimationFrame(this.animationLoop);
    this.geofenceService.load();
  }

  ngOnDestroy(): void {
    if (this.frameId !== undefined) {
      cancelAnimationFrame(this.frameId);
      this.frameId = undefined;
    }
    this.map?.remove();
    this.map = undefined;
    this.styleLoaded.set(false);
    if (isE2eHarness()) {
      delete (window as unknown as { __fleetpulseLiveMap?: MapLibreMap }).__fleetpulseLiveMap;
    }
  }

  // Geolocation fallback when denied/unavailable/timed out: prefer fitting
  // the fleet's currently known vehicle positions over the bare DEFAULT_
  // CENTER/DEFAULT_ZOOM the map was already created with -- if none are
  // known yet, that default view is simply left as-is and `false` tells the
  // caller not to treat the initial centering as resolved yet.
  private centerOnFleetOrDefault(map: MapLibreMap): boolean {
    const known = [...this.fleetStore.vehicles().values()].filter(
      (vehicle): vehicle is VehicleState & { lat: number; lon: number } =>
        typeof vehicle.lat === 'number' && typeof vehicle.lon === 'number',
    );
    if (known.length === 0) {
      return false;
    }
    const lats = known.map((vehicle) => vehicle.lat);
    const lons = known.map((vehicle) => vehicle.lon);
    map.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)],
      ],
      { padding: 64, maxZoom: 14 },
    );
    return true;
  }

  // Task 4.4: the rAF loop itself -- the one piece that genuinely has to
  // live in the component, since it drives a real browser API. Everything
  // it calls (sampleAll, toVehicleFeatureCollection) is pure and unit-tested
  // independently.
  private readonly animationLoop = (): void => {
    this.updateVehiclePositions();
    this.frameId = requestAnimationFrame(this.animationLoop);
  };

  private updateVehiclePositions(): void {
    if (!this.styleLoaded() || !this.map) {
      return;
    }
    const positions = this.interpolation.sampleAll(performance.now());
    const collection = toVehicleFeatureCollection(this.latestVehicles, positions, this.fleetStore.selectedVehicleId());
    const source = this.map.getSource(VEHICLES_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(collection);
  }

  private renderTrack(features: TrackSegmentFeature[]): void {
    if (!this.styleLoaded() || !this.map) {
      return;
    }
    const source = this.map.getSource(TRACK_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(features.length > 0 ? { type: 'FeatureCollection', features } : EMPTY_TRACK_COLLECTION);
  }

  // Task 5.3: same pure mapper the geofencing editor's own drawing-context
  // layer uses (geofence-geometry.util.ts), so a geofence never renders
  // differently depending on which screen shows it.
  private renderGeofences(geofences: readonly GeofenceResponse[]): void {
    if (!this.styleLoaded() || !this.map) {
      return;
    }
    const source = this.map.getSource(GEOFENCES_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(toGeofenceFeatureCollection(geofences));
  }

  // Draws a small filled triangle onto an offscreen canvas and registers it
  // as an SDF image, instead of shipping/loading an external icon asset:
  // SDF images can be recolored per-feature via `icon-color` (task 4.3's
  // "color by motion state"), which a plain raster image cannot.
  private registerVehicleIcon(map: MapLibreMap): void {
    if (map.hasImage(VEHICLE_ICON_ID)) {
      return;
    }
    const size = 32;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext('2d');
    if (!context) {
      return;
    }
    context.beginPath();
    context.moveTo(size / 2, 2);
    context.lineTo(size - 4, size - 4);
    context.lineTo(size / 2, size - 12);
    context.lineTo(4, size - 4);
    context.closePath();
    context.fillStyle = '#000000';
    context.fill();
    map.addImage(VEHICLE_ICON_ID, context.getImageData(0, 0, size, size), { sdf: true });
  }

  private addVehicleLayer(map: MapLibreMap): void {
    map.addSource(VEHICLES_SOURCE_ID, {
      type: 'geojson',
      data: { type: 'FeatureCollection', features: [] },
    });
    map.addLayer({
      id: VEHICLES_LAYER_ID,
      type: 'symbol',
      source: VEHICLES_SOURCE_ID,
      layout: {
        'icon-image': VEHICLE_ICON_ID,
        // Task 4.3: oriented by reported heading, not by movement direction
        // computed here -- the wire telemetry payload already carries it.
        'icon-rotate': ['get', 'heading'],
        'icon-rotation-alignment': 'map',
        'icon-allow-overlap': true,
        // Task 5.2: the selected vehicle's marker renders larger -- the
        // "highlight" half of list<->map selection sync (the other half is
        // the flyTo centering effect in the constructor above).
        'icon-size': ['case', ['==', ['get', 'selected'], true], 1.15, 0.8],
      },
      paint: {
        // Task 4.3: color by motion state.
        'icon-color': [
          'match',
          ['get', 'motionState'],
          'MOVING',
          '#22c55e',
          'IDLING',
          '#eab308',
          'STOPPED',
          '#6b7280',
          /* default */ '#6b7280',
        ],
        // Task 4.3/4.5: dimmed if offline OR if no recent reported position
        // (`stale`, task 4.5) -- two independent triggers for the same
        // visual treatment.
        'icon-opacity': ['case', ['any', ['==', ['get', 'online'], false], ['get', 'stale']], 0.35, 1],
      },
    });
  }

  private addTrackLayer(map: MapLibreMap): void {
    map.addSource(TRACK_SOURCE_ID, { type: 'geojson', data: EMPTY_TRACK_COLLECTION });
    map.addLayer({
      id: TRACK_LAYER_ID,
      type: 'line',
      source: TRACK_SOURCE_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': TRACK_LINE_COLOR, 'line-width': 3 },
    });
  }

  // Task 5.3: the exact same fill+outline styling the geofencing editor's
  // own context layer uses for "existing geofences", so a geofence looks
  // identical whether seen from this live map or from the editor.
  private addGeofenceLayer(map: MapLibreMap): void {
    map.addSource(GEOFENCES_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    map.addLayer({
      id: GEOFENCES_LAYER_ID,
      type: 'fill',
      source: GEOFENCES_SOURCE_ID,
      paint: { 'fill-color': '#2563eb', 'fill-opacity': 0.15 },
    });
    map.addLayer({
      id: GEOFENCES_OUTLINE_LAYER_ID,
      type: 'line',
      source: GEOFENCES_SOURCE_ID,
      paint: { 'line-color': '#2563eb', 'line-width': 2 },
    });
  }
}
