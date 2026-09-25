import type { Feature, LineString } from 'geojson';
import type { TrackPointResponse } from '@fleetpulse/api-client';

export type TrackSegmentFeature = Feature<LineString, Record<string, never>>;

type ValidTrackPoint = TrackPointResponse & { lat: number; lon: number };

const EARTH_RADIUS_KM = 6371;

// Prod QA (2026-09-24): a straight line drawn across a simulator teleport
// read as a real, physically travelled path. The jump test is implied speed
// only: the backend simplifies the track (Geo.simplifyTrack), so consecutive
// points minutes apart at a plausible speed are real driving, not a gap.
// Movements within the jitter tolerance never split, so GPS noise between
// fixes sharing a timestamp does not shatter the line.
export const TRACK_MAX_SPEED_KMH = 250;
export const TRACK_JITTER_TOLERANCE_KM = 0.05;

// Pure split, deliberately returned before any GeoJSON shaping so the
// "where does a jump happen" decision is unit-testable on its own, same as
// `geofence-geometry.util.ts`'s own hand-rolled distance math (no turf.js in
// package.json) -- duplicated here rather than shared since this feature has
// no geo-core-equivalent module to pull it from either.
export function splitTrackIntoSegments(points: readonly TrackPointResponse[]): ValidTrackPoint[][] {
  const valid = points.filter(
    (point): point is ValidTrackPoint => typeof point.lat === 'number' && typeof point.lon === 'number',
  );
  if (valid.length === 0) {
    return [];
  }

  const segments: ValidTrackPoint[][] = [[valid[0]]];
  for (let i = 1; i < valid.length; i++) {
    const previous = valid[i - 1];
    const current = valid[i];
    if (isImplausibleJump(previous, current)) {
      segments.push([current]);
    } else {
      segments[segments.length - 1].push(current);
    }
  }
  return segments;
}

// Task 4.6 (extended): the line feature(s) LiveMapComponent draws directly
// via `source.setData(...)`. One `Feature` per segment (a MultiLineString
// would draw the exact same pixels, but keeping segments as separate
// Features needs no geometry-type branching downstream and matches how
// `toGeofenceFeatureCollection` already returns one Feature per geofence).
// Requirement "Independencia del mapa en vivo respecto al servicio HTTP":
// this resource failing (`track.error()`) never touches FleetStore or the
// live vehicle layer -- only this line goes empty, the map itself keeps
// updating from MQTT.
export function toTrackSegmentFeatures(points: TrackPointResponse[] | undefined): TrackSegmentFeature[] {
  if (!points) {
    return [];
  }
  const features: TrackSegmentFeature[] = [];
  for (const segment of splitTrackIntoSegments(points)) {
    if (segment.length < 2) {
      // A lone point either side of a jump has nothing to draw a line to.
      continue;
    }
    features.push({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: segment.map((point): [number, number] => [point.lon, point.lat]) },
      properties: {},
    });
  }
  return features;
}

function isImplausibleJump(a: ValidTrackPoint, b: ValidTrackPoint): boolean {
  const gapMinutes = minutesBetween(a.recordedAt, b.recordedAt);
  if (gapMinutes === undefined) {
    // No usable timestamps to judge plausibility -- never split on a
    // guess, matching the toTrackSegmentFeatures precedent of dropping
    // points rather than fabricating data.
    return false;
  }
  const distanceKm = haversineDistanceKm(a, b);
  if (distanceKm <= TRACK_JITTER_TOLERANCE_KM) {
    return false;
  }
  if (gapMinutes <= 0) {
    // Duplicate or reversed timestamp with real movement: undefined or
    // infinite speed, an implausible jump by definition.
    return true;
  }
  return distanceKm / (gapMinutes / 60) > TRACK_MAX_SPEED_KMH;
}

function minutesBetween(fromIso: string | undefined, toIso: string | undefined): number | undefined {
  if (!fromIso || !toIso) {
    return undefined;
  }
  const fromMs = Date.parse(fromIso);
  const toMs = Date.parse(toIso);
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs)) {
    return undefined;
  }
  return (toMs - fromMs) / 60_000;
}

function haversineDistanceKm(a: ValidTrackPoint, b: ValidTrackPoint): number {
  const toRad = (deg: number): number => (deg * Math.PI) / 180;
  const deltaLat = toRad(b.lat - a.lat);
  const deltaLon = toRad(b.lon - a.lon);
  const sinLat = Math.sin(deltaLat / 2);
  const sinLon = Math.sin(deltaLon / 2);
  const h = sinLat * sinLat + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * sinLon * sinLon;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
}
