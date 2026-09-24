import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map as mapOperator } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { Map as MapLibreMap } from 'maplibre-gl';
import type { GeoJSONSource, MapMouseEvent } from 'maplibre-gl';
import { Button } from 'primeng/button';
import type { GeofenceResponse } from '@fleetpulse/api-client';
import { GeolocationService, type GeolocationPoint } from '../../../core/geolocation/geolocation.service';
import type { GeofenceDraft, GeofencePoint } from '../models/geofence-draft.model';
import {
  distanceMeters,
  draftToFeature,
  draftVerticesToFeatureCollection,
  toGeofenceFeatureCollection,
} from '../services/geofence-geometry.util';

// Task 5.1's own design decision, documented per this change's "note
// deviations, don't silently freelance" convention (same as WU2's
// ST_Contains->ST_Covers, WU3's pending_reading_count gap): no drawing
// library (maplibre-gl-draw or equivalent) is in package.json, and adding
// one was not taken -- hand-rolled click-to-vertex drawing on top of the
// vanilla MapLibre GL JS API keeps this consistent with 04-add-live-map's
// own established "no DOM markers, GeoJSON source + layer only" convention
// (LiveMapComponent), avoids a new dependency this project's conventions
// require checking for first, and the editor's exact geometry needs (an open
// polygon ring OR a center+radiusMeters pair, matching GeofenceRequest) are
// narrow enough that a general-purpose drawing library would mostly be
// worked around anyway.
const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

const CONTEXT_SOURCE_ID = 'geofence-context';
const CONTEXT_LAYER_ID = 'geofence-context-layer';
const CONTEXT_OUTLINE_LAYER_ID = 'geofence-context-outline-layer';
const DRAFT_SOURCE_ID = 'geofence-draft';
const DRAFT_LAYER_ID = 'geofence-draft-layer';
const DRAFT_OUTLINE_LAYER_ID = 'geofence-draft-outline-layer';
const DRAFT_VERTICES_SOURCE_ID = 'geofence-draft-vertices';
const DRAFT_VERTICES_LAYER_ID = 'geofence-draft-vertices-layer';

const EMPTY_POLYGON_COLLECTION = { type: 'FeatureCollection' as const, features: [] };

// Same fallback/zoom convention as LiveMapComponent's own geolocation
// centering (user decision, 2026-09-24): create the map at this view
// immediately, never waiting on the geolocation permission prompt.
const DEFAULT_CENTER: [number, number] = [0, 0];
const DEFAULT_ZOOM = 2;
const USER_LOCATION_ZOOM = 12;

type DrawingMode = 'idle' | 'polygon' | 'circle';

interface GeolocationOutcome {
  readonly settled: boolean;
  readonly point: GeolocationPoint | undefined;
}

const PENDING_GEOLOCATION: GeolocationOutcome = { settled: false, point: undefined };

// Task 5.1 (+ 5.2 console half): owns one MapLibre map instance dedicated to
// drawing -- a separate instance from LiveMapComponent's (task 5.3), since
// stray clicks while tracking vehicles must never add a geofence vertex by
// accident, and this component's `doubleClickZoom.disable()` below would be
// wrong behavior on the live tracking map. Emits a committed `GeofenceDraft`
// whenever the user finishes a shape; GeofenceEditorPageComponent (the
// container) owns turning that into a GeofenceRequest and calling the CRUD
// API -- this component has zero knowledge of name/rule/dwellSecs or HTTP.
@Component({
  selector: 'app-geofence-drawing-editor',
  imports: [Button, DecimalPipe],
  templateUrl: './geofence-drawing-editor.component.html',
  styleUrl: './geofence-drawing-editor.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeofenceDrawingEditorComponent implements AfterViewInit, OnDestroy {
  private readonly mapContainer = viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');
  private readonly geolocationService = inject(GeolocationService);

  // Rendered as read-only context while drawing, so the user can see
  // overlaps with already-saved geofences without leaving the editor.
  readonly existingGeofences = input<readonly GeofenceResponse[]>([]);
  // Incrementing this from the container clears any in-progress or committed
  // draft -- used for both an explicit "New geofence" action and after a
  // successful save, without this component needing an imperative method.
  readonly resetToken = input<number>(0);

  readonly draftChange = output<GeofenceDraft | undefined>();

  protected readonly mode = signal<DrawingMode>('idle');
  private readonly vertices = signal<GeofencePoint[]>([]);
  private readonly circleCenter = signal<GeofencePoint | undefined>(undefined);
  protected readonly circleRadiusMeters = signal(0);
  private readonly committedDraft = signal<GeofenceDraft | undefined>(undefined);
  private readonly styleLoaded = signal(false);

  protected readonly canFinishPolygon = computed(() => this.mode() === 'polygon' && this.vertices().length >= 3);
  protected readonly vertexCount = computed(() => this.vertices().length);
  protected readonly hasCircleCenter = computed(() => this.circleCenter() !== undefined);
  protected readonly hasDraft = computed(() => this.committedDraft() !== undefined);

  // Only a *finished* shape is ever emitted/previewed as a filled polygon;
  // while drawing a polygon with fewer than 3 vertices there is nothing to
  // close yet, so only the vertex markers layer shows feedback.
  private readonly previewDraft = computed<GeofenceDraft | undefined>(() => this.committedDraft() ?? this.inProgressDraft());

  private readonly inProgressDraft = computed<GeofenceDraft | undefined>(() => {
    if (this.mode() === 'polygon' && this.vertices().length >= 3) {
      return { shape: 'POLYGON', vertices: this.vertices() };
    }
    const center = this.circleCenter();
    if (this.mode() === 'circle' && center && this.circleRadiusMeters() > 0) {
      return { shape: 'CIRCLE', center, radiusMeters: this.circleRadiusMeters() };
    }
    return undefined;
  });

  private map: MapLibreMap | undefined;
  // Same "user gesture vs. programmatic move" and "settled vs. still
  // pending" convention as LiveMapComponent's own geolocation centering.
  private readonly userInteracted = signal(false);
  private readonly geolocationOutcome = toSignal(
    this.geolocationService.position().pipe(mapOperator((point): GeolocationOutcome => ({ settled: true, point }))),
    { initialValue: PENDING_GEOLOCATION },
  );
  private readonly initialCenterApplied = signal(false);

  constructor() {
    effect(() => {
      this.draftChange.emit(this.committedDraft());
    });

    effect(() => {
      if (!this.styleLoaded() || !this.map) {
        return;
      }
      this.renderDraft(this.previewDraft());
    });

    effect(() => {
      if (!this.styleLoaded() || !this.map) {
        return;
      }
      this.renderContext(this.existingGeofences());
    });

    effect(() => {
      this.resetToken();
      this.resetDrawingState();
    });

    // User decision (2026-09-24): center the drawing map on the dispatcher's
    // own location once geolocation resolves, unless they already interacted
    // with the map (drag/zoom) -- they may already be positioning the map
    // over the area they intend to draw a geofence on. Never blocks map
    // creation (ngAfterViewInit below always creates the map at DEFAULT_
    // CENTER/DEFAULT_ZOOM first); falls back to that same default view if
    // geolocation is denied/unavailable/timed out.
    effect(() => {
      const map = this.map;
      // Read unconditionally, before any early return -- same reasoning as
      // LiveMapComponent's own geolocation-centering effect: an effect only
      // reacts to a signal it actually read on its last run, so reading this
      // behind the guards below would miss a resolution that lands before
      // `styleLoaded`/`map` are ready.
      const outcome = this.geolocationOutcome();
      if (!map || !this.styleLoaded() || this.initialCenterApplied()) {
        return;
      }
      if (this.userInteracted()) {
        this.initialCenterApplied.set(true);
        return;
      }
      if (!outcome.settled) {
        return;
      }
      this.initialCenterApplied.set(true);
      if (outcome.point) {
        map.easeTo({ center: [outcome.point.lon, outcome.point.lat], zoom: USER_LOCATION_ZOOM });
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
    // easeTo (this component's own centering) never does.
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
      map.addSource(CONTEXT_SOURCE_ID, { type: 'geojson', data: EMPTY_POLYGON_COLLECTION });
      map.addLayer({
        id: CONTEXT_LAYER_ID,
        type: 'fill',
        source: CONTEXT_SOURCE_ID,
        paint: { 'fill-color': '#2563eb', 'fill-opacity': 0.15 },
      });
      map.addLayer({
        id: CONTEXT_OUTLINE_LAYER_ID,
        type: 'line',
        source: CONTEXT_SOURCE_ID,
        paint: { 'line-color': '#2563eb', 'line-width': 2 },
      });

      map.addSource(DRAFT_SOURCE_ID, { type: 'geojson', data: EMPTY_POLYGON_COLLECTION });
      map.addLayer({
        id: DRAFT_LAYER_ID,
        type: 'fill',
        source: DRAFT_SOURCE_ID,
        paint: { 'fill-color': '#16a34a', 'fill-opacity': 0.25 },
      });
      map.addLayer({
        id: DRAFT_OUTLINE_LAYER_ID,
        type: 'line',
        source: DRAFT_SOURCE_ID,
        paint: { 'line-color': '#16a34a', 'line-width': 2 },
      });

      map.addSource(DRAFT_VERTICES_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addLayer({
        id: DRAFT_VERTICES_LAYER_ID,
        type: 'circle',
        source: DRAFT_VERTICES_SOURCE_ID,
        paint: { 'circle-radius': 5, 'circle-color': '#16a34a' },
      });

      // Drawing is click-driven; double-click-to-zoom would fight a user
      // trying to place two vertices quickly near each other.
      map.doubleClickZoom.disable();

      map.on('click', (event: MapMouseEvent) => {
        this.handleMapClick({ lat: event.lngLat.lat, lon: event.lngLat.lng });
      });
      map.on('mousemove', (event: MapMouseEvent) => {
        this.handleMapMouseMove({ lat: event.lngLat.lat, lon: event.lngLat.lng });
      });

      this.styleLoaded.set(true);
    });
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = undefined;
    this.styleLoaded.set(false);
  }

  protected startPolygon(): void {
    this.resetDrawingState();
    this.mode.set('polygon');
  }

  protected startCircle(): void {
    this.resetDrawingState();
    this.mode.set('circle');
  }

  protected finishPolygon(): void {
    if (this.vertices().length < 3) {
      return;
    }
    this.committedDraft.set({ shape: 'POLYGON', vertices: this.vertices() });
    this.mode.set('idle');
  }

  protected cancelDrawing(): void {
    this.resetDrawingState();
  }

  private handleMapClick(point: GeofencePoint): void {
    const mode = this.mode();
    if (mode === 'polygon') {
      this.vertices.update((current) => [...current, point]);
      return;
    }
    if (mode === 'circle') {
      const center = this.circleCenter();
      if (!center) {
        this.circleCenter.set(point);
        return;
      }
      const radiusMeters = distanceMeters(center, point);
      this.circleRadiusMeters.set(radiusMeters);
      this.committedDraft.set({ shape: 'CIRCLE', center, radiusMeters });
      this.mode.set('idle');
    }
  }

  private handleMapMouseMove(point: GeofencePoint): void {
    const center = this.circleCenter();
    if (this.mode() === 'circle' && center) {
      this.circleRadiusMeters.set(distanceMeters(center, point));
    }
  }

  private resetDrawingState(): void {
    this.mode.set('idle');
    this.vertices.set([]);
    this.circleCenter.set(undefined);
    this.circleRadiusMeters.set(0);
    this.committedDraft.set(undefined);
  }

  private renderDraft(draft: GeofenceDraft | undefined): void {
    const shapeSource = this.map?.getSource(DRAFT_SOURCE_ID) as GeoJSONSource | undefined;
    const feature = draftToFeature(draft);
    shapeSource?.setData(feature ? { type: 'FeatureCollection', features: [feature] } : EMPTY_POLYGON_COLLECTION);

    const verticesSource = this.map?.getSource(DRAFT_VERTICES_SOURCE_ID) as GeoJSONSource | undefined;
    verticesSource?.setData(draftVerticesToFeatureCollection(draft));
  }

  private renderContext(geofences: readonly GeofenceResponse[]): void {
    const source = this.map?.getSource(CONTEXT_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(toGeofenceFeatureCollection(geofences));
  }
}
