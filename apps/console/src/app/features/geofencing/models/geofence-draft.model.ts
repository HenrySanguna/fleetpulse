// Task 5.1: a drawn-but-not-yet-saved geofence shape. Deliberately not
// `GeofenceRequest` itself (libs/api-client) -- a draft only ever carries
// geometry, never name/rule/dwellSecs, which the editor page's own reactive
// form owns. Kept as a discriminated union (never both vertices and
// center/radius at once) so the drawing editor can never produce a shape the
// backend's own GeofenceRequest validation would reject.
export interface GeofencePoint {
  readonly lat: number;
  readonly lon: number;
}

export interface GeofencePolygonDraft {
  readonly shape: 'POLYGON';
  readonly vertices: readonly GeofencePoint[];
}

export interface GeofenceCircleDraft {
  readonly shape: 'CIRCLE';
  readonly center: GeofencePoint;
  readonly radiusMeters: number;
}

export type GeofenceDraft = GeofencePolygonDraft | GeofenceCircleDraft;
