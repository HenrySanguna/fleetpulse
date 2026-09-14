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
import { Map as MapLibreMap } from 'maplibre-gl';
import type { FeatureCollection, LineString } from 'geojson';
import type { GeoJSONSource, MapLayerMouseEvent } from 'maplibre-gl';
import { FleetStore } from '../services/fleet.store';
import { VehicleTrackService } from '../services/vehicle-track.service';
import { VehicleInterpolationEngine } from '../services/vehicle-interpolation';
import { toVehicleFeatureCollection } from '../services/vehicle-symbol.util';
import type { VehicleState } from '../models/vehicle-state.model';
import type { TrackLineFeature } from '../services/vehicle-track.service';

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

  private readonly interpolation = new VehicleInterpolationEngine();
  private latestVehicles: readonly VehicleState[] = [];
  private map: MapLibreMap | undefined;
  private frameId: number | undefined;
  // A signal (not a plain field) specifically so the task 5.2 centering
  // effect below re-runs once the map finishes loading, even if a selection
  // already happened before that -- effects only re-run when a *signal*
  // dependency changes, not a plain class field mutation.
  private readonly styleLoaded = signal(false);

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
      this.renderTrack(this.trackService.trackLine());
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
  }

  ngAfterViewInit(): void {
    const map = new MapLibreMap({
      container: this.mapContainer().nativeElement,
      style: MAP_STYLE_URL,
      center: [0, 0],
      zoom: 2,
    });
    this.map = map;

    map.on('load', () => {
      this.registerVehicleIcon(map);
      this.addVehicleLayer(map);
      this.addTrackLayer(map);
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

      this.renderTrack(this.trackService.trackLine());
    });

    this.frameId = requestAnimationFrame(this.animationLoop);
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

  private renderTrack(feature: TrackLineFeature | undefined): void {
    if (!this.styleLoaded() || !this.map) {
      return;
    }
    const source = this.map.getSource(TRACK_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(feature ? { type: 'FeatureCollection', features: [feature] } : EMPTY_TRACK_COLLECTION);
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
      paint: { 'line-color': '#3b82f6', 'line-width': 3 },
    });
  }
}
