import type { Feature, FeatureCollection, Point, Polygon } from 'geojson';
import type { GeoPointRequest, GeoPointResponse, GeofenceResponse } from '@fleetpulse/api-client';
import type { GeofenceCircleDraft, GeofenceDraft, GeofencePoint, GeofencePolygonDraft } from '../models/geofence-draft.model';

const EARTH_RADIUS_METERS = 6_371_000;

// Task 5.1: the radius for a drawn circle (center click + edge click) and
// the visual preview for both existing and in-progress geofences all need
// plain lat/lon distance/destination math -- no turf.js or similar dependency
// exists in package.json (checked first, per project convention), so this is
// the same hand-rolled-math choice design.md's own processor-side `Geo`
// helper already made for the backend, just duplicated here since the
// console has no shared `geo-core`-equivalent TS module to import from.
export function distanceMeters(a: GeofencePoint, b: GeofencePoint): number {
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const deltaLat = toRadians(b.lat - a.lat);
  const deltaLon = toRadians(b.lon - a.lon);
  const sinLat = Math.sin(deltaLat / 2);
  const sinLon = Math.sin(deltaLon / 2);
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
}

// Approximates a geodesic circle as a 64-vertex polygon ring around `center`
// -- the same "circle is a buffered polygon" convention task 1.3/WU1 already
// proved server-side (PostGIS's own ST_Buffer), just computed client-side so
// the drawing preview and the request's `vertices` (if ever needed) agree
// with what the user sees while dragging.
export function circlePolygonVertices(center: GeofencePoint, radiusMeters: number, segments = 64): GeofencePoint[] {
  const latRadians = toRadians(center.lat);
  const vertices: GeofencePoint[] = [];
  for (let i = 0; i < segments; i++) {
    const bearing = (2 * Math.PI * i) / segments;
    const angularDistance = radiusMeters / EARTH_RADIUS_METERS;
    const destLat = Math.asin(
      Math.sin(latRadians) * Math.cos(angularDistance) + Math.cos(latRadians) * Math.sin(angularDistance) * Math.cos(bearing),
    );
    const destLon =
      toRadians(center.lon) +
      Math.atan2(
        Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latRadians),
        Math.cos(angularDistance) - Math.sin(latRadians) * Math.sin(destLat),
      );
    vertices.push({ lat: toDegrees(destLat), lon: toDegrees(destLon) });
  }
  return vertices;
}

// `GeofenceResponse.vertices` is always the CLOSED ring PostGIS stored
// (task 1.3/WU6: a circle's vertices are its ST_Buffer'd polygon, repeating
// the first point as the last). `GeofenceRequest.vertices` is the OPEN ring
// the server closes itself (WU6's own GeofenceRequest doc comment) -- this
// drops that trailing duplicate so a geometry read back from the API can be
// resent as a request without the server seeing a degenerate extra vertex.
export function openRing(vertices: readonly GeoPointResponse[]): GeofencePoint[] {
  const points = vertices
    .filter((v): v is GeoPointResponse & { lat: number; lon: number } => typeof v.lat === 'number' && typeof v.lon === 'number')
    .map((v) => ({ lat: v.lat, lon: v.lon }));
  if (points.length < 2) {
    return points;
  }
  const first = points[0];
  const last = points[points.length - 1];
  if (first && last && first.lat === last.lat && first.lon === last.lon) {
    return points.slice(0, -1);
  }
  return points;
}

export function toGeoPointRequests(points: readonly GeofencePoint[]): GeoPointRequest[] {
  return points.map((p) => ({ lat: p.lat, lon: p.lon }));
}

type GeofenceProperties = { readonly id: string; readonly name: string };

// Task 5.3: one GeoJSON fill+outline layer shows every active geofence, on
// the live map (LiveMapComponent) and as editing context on the drawing
// editor's own map -- both consume this same pure mapper so neither can
// silently drift from the other's notion of "how a geofence renders".
export function toGeofenceFeatureCollection(geofences: readonly GeofenceResponse[]): FeatureCollection<Polygon, GeofenceProperties> {
  const features: Feature<Polygon, GeofenceProperties>[] = [];
  for (const geofence of geofences) {
    if (!geofence.id || !geofence.vertices || geofence.vertices.length < 3) {
      continue;
    }
    const ring = toCoordinateRing(geofence.vertices);
    if (!ring) {
      continue;
    }
    features.push({
      type: 'Feature',
      geometry: { type: 'Polygon', coordinates: [ring] },
      properties: { id: geofence.id, name: geofence.name ?? geofence.id },
    });
  }
  return { type: 'FeatureCollection', features };
}

// Task 5.1: the in-progress drawing's own preview -- a committed draft
// renders as a closed polygon (circle drafts via circlePolygonVertices
// above); an unfinished polygon (fewer than 3 vertices, or not yet closed)
// has no polygon to show at all, left for the caller's vertex-points layer.
export function draftToFeature(draft: GeofenceDraft | undefined): Feature<Polygon> | undefined {
  if (!draft) {
    return undefined;
  }
  if (draft.shape === 'CIRCLE') {
    return circleDraftToFeature(draft);
  }
  return polygonDraftToFeature(draft);
}

export function draftVerticesToFeatureCollection(draft: GeofenceDraft | undefined): FeatureCollection<Point> {
  if (!draft || draft.shape !== 'POLYGON') {
    return { type: 'FeatureCollection', features: [] };
  }
  return {
    type: 'FeatureCollection',
    features: draft.vertices.map((vertex) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [vertex.lon, vertex.lat] },
      properties: {},
    })),
  };
}

function polygonDraftToFeature(draft: GeofencePolygonDraft): Feature<Polygon> | undefined {
  if (draft.vertices.length < 3) {
    return undefined;
  }
  const ring: [number, number][] = draft.vertices.map((v) => [v.lon, v.lat]);
  const first = draft.vertices[0];
  if (first) {
    ring.push([first.lon, first.lat]);
  }
  return { type: 'Feature', geometry: { type: 'Polygon', coordinates: [ring] }, properties: {} };
}

function circleDraftToFeature(draft: GeofenceCircleDraft): Feature<Polygon> | undefined {
  if (draft.radiusMeters <= 0) {
    return undefined;
  }
  const ring = circlePolygonVertices(draft.center, draft.radiusMeters);
  const coordinates: [number, number][] = ring.map((v) => [v.lon, v.lat]);
  const first = ring[0];
  if (first) {
    coordinates.push([first.lon, first.lat]);
  }
  return { type: 'Feature', geometry: { type: 'Polygon', coordinates: [coordinates] }, properties: {} };
}

// Task 5.2/prod-QA fix: fits the map to a saved geofence's own geometry when
// selected from the list. `GeofenceResponse.vertices` is always the buffered
// polygon (see openRing's doc comment -- a circle's vertices ARE its
// ST_Buffer'd polygon), so bounding-box math over them covers polygons and
// circles alike without needing a separate center/radius branch.
export function geofenceBounds(geofence: GeofenceResponse): [[number, number], [number, number]] | undefined {
  const vertices = (geofence.vertices ?? []).filter(
    (vertex): vertex is GeoPointResponse & { lat: number; lon: number } =>
      typeof vertex.lat === 'number' && typeof vertex.lon === 'number',
  );
  if (vertices.length === 0) {
    return undefined;
  }
  const lats = vertices.map((vertex) => vertex.lat);
  const lons = normalizeAntimeridianSpan(vertices.map((vertex) => vertex.lon));
  return [
    [Math.min(...lons), Math.min(...lats)],
    [Math.max(...lons), Math.max(...lats)],
  ];
}

// R3-bounds-antimeridian: plain min/max longitude picks the LONG way around
// the globe for a geofence whose vertices straddle the antimeridian (e.g.
// -179 and 179 -- 2 degrees apart the short way, but ~358 by plain
// subtraction). A span over 180 degrees is this app's signal for that case
// (real geofences are small, local shapes -- never intentionally wider than
// half the globe), so every negative longitude shifts by +360, turning the
// straddling pair into a normal, narrow span east of 180 (179 and 181).
// MapLibre's `fitBounds` accepts an east edge past 180.
function normalizeAntimeridianSpan(lons: readonly number[]): number[] {
  const span = Math.max(...lons) - Math.min(...lons);
  if (span <= 180) {
    return [...lons];
  }
  return lons.map((lon) => (lon < 0 ? lon + 360 : lon));
}

function toCoordinateRing(vertices: readonly GeoPointResponse[]): [number, number][] | undefined {
  const coordinates: [number, number][] = [];
  for (const vertex of vertices) {
    if (typeof vertex.lat !== 'number' || typeof vertex.lon !== 'number') {
      return undefined;
    }
    coordinates.push([vertex.lon, vertex.lat]);
  }
  return coordinates;
}

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

function toDegrees(radians: number): number {
  return (radians * 180) / Math.PI;
}
