import type { GeofenceResponse } from '@fleetpulse/api-client';
import type { GeofenceDraft } from '../models/geofence-draft.model';
import {
  circlePolygonVertices,
  distanceMeters,
  draftToFeature,
  draftVerticesToFeatureCollection,
  geofenceBounds,
  openRing,
  toGeoPointRequests,
  toGeofenceFeatureCollection,
} from './geofence-geometry.util';

describe('distanceMeters', () => {
  it('returns 0 for the same point', () => {
    expect(distanceMeters({ lat: 10, lon: 20 }, { lat: 10, lon: 20 })).toBe(0);
  });

  it('approximates one degree of latitude as roughly 111km', () => {
    const meters = distanceMeters({ lat: 0, lon: 0 }, { lat: 1, lon: 0 });
    expect(meters).toBeGreaterThan(110_000);
    expect(meters).toBeLessThan(112_000);
  });
});

describe('circlePolygonVertices', () => {
  it('generates the requested number of vertices, all at roughly the given radius from the center', () => {
    const center = { lat: 40, lon: -3 };
    const radiusMeters = 250;
    const vertices = circlePolygonVertices(center, radiusMeters, 16);

    expect(vertices).toHaveLength(16);
    for (const vertex of vertices) {
      expect(distanceMeters(center, vertex)).toBeCloseTo(radiusMeters, 0);
    }
  });
});

describe('openRing', () => {
  it('drops the closing vertex when the ring repeats the first point', () => {
    const ring = [
      { lat: 1, lon: 1 },
      { lat: 2, lon: 2 },
      { lat: 3, lon: 1 },
      { lat: 1, lon: 1 },
    ];

    expect(openRing(ring)).toEqual([{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }]);
  });

  it('leaves an already-open ring untouched', () => {
    const ring = [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }];

    expect(openRing(ring)).toEqual(ring);
  });

  it('ignores vertices missing lat/lon', () => {
    expect(openRing([{ lat: undefined, lon: undefined }, { lat: 1, lon: 1 }])).toEqual([{ lat: 1, lon: 1 }]);
  });
});

describe('toGeoPointRequests', () => {
  it('maps GeofencePoint[] to GeoPointRequest[]', () => {
    expect(toGeoPointRequests([{ lat: 1, lon: 2 }])).toEqual([{ lat: 1, lon: 2 }]);
  });
});

describe('toGeofenceFeatureCollection', () => {
  it('maps each geofence with a closed ring to a Polygon feature', () => {
    const geofences: GeofenceResponse[] = [
      {
        id: 'g1',
        name: 'Depot',
        vertices: [
          { lat: 1, lon: 1 },
          { lat: 2, lon: 2 },
          { lat: 3, lon: 1 },
          { lat: 1, lon: 1 },
        ],
      },
    ];

    const collection = toGeofenceFeatureCollection(geofences);

    expect(collection.features).toHaveLength(1);
    expect(collection.features[0]?.properties).toEqual({ id: 'g1', name: 'Depot' });
    expect(collection.features[0]?.geometry.coordinates[0]).toHaveLength(4);
  });

  it('skips a geofence with fewer than 3 vertices or missing id', () => {
    const geofences: GeofenceResponse[] = [
      { id: 'g1', name: 'Too few', vertices: [{ lat: 1, lon: 1 }] },
      { name: 'No id', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] },
    ];

    expect(toGeofenceFeatureCollection(geofences).features).toEqual([]);
  });
});

describe('geofenceBounds', () => {
  it('returns the min/max lon/lat bounding box for a polygon geofence', () => {
    const geofence: GeofenceResponse = {
      id: 'g1',
      name: 'Depot',
      vertices: [
        { lat: 10, lon: 20 },
        { lat: 30, lon: 5 },
        { lat: 20, lon: 15 },
      ],
    };

    expect(geofenceBounds(geofence)).toEqual([
      [5, 10],
      [20, 30],
    ]);
  });

  it('returns the same point twice for a single-vertex geofence', () => {
    expect(geofenceBounds({ vertices: [{ lat: 1, lon: 2 }] })).toEqual([
      [2, 1],
      [2, 1],
    ]);
  });

  it('ignores vertices missing lat/lon', () => {
    expect(geofenceBounds({ vertices: [{ lat: undefined, lon: undefined }, { lat: 1, lon: 2 }] })).toEqual([
      [2, 1],
      [2, 1],
    ]);
  });

  it('returns undefined when there are no valid vertices', () => {
    expect(geofenceBounds({ vertices: [] })).toBeUndefined();
    expect(geofenceBounds({})).toBeUndefined();
  });

  // R3-bounds-antimeridian: a plain min/max over -179/179 gives [-179, 179],
  // a ~358 degree (near-global) box the long way around -- the short way
  // across the antimeridian is only 2 degrees wide.
  it('gives a narrow box the short way around for a geofence crossing the antimeridian', () => {
    const geofence: GeofenceResponse = {
      id: 'g1',
      name: 'Strait',
      vertices: [
        { lat: 10, lon: 179 },
        { lat: 20, lon: -179 },
        { lat: 15, lon: 179.5 },
      ],
    };

    expect(geofenceBounds(geofence)).toEqual([
      [179, 10],
      [181, 20],
    ]);
  });
});

describe('draftToFeature', () => {
  it('returns undefined for no draft', () => {
    expect(draftToFeature(undefined)).toBeUndefined();
  });

  it('returns undefined for a polygon draft with fewer than 3 vertices', () => {
    const draft: GeofenceDraft = { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }] };
    expect(draftToFeature(draft)).toBeUndefined();
  });

  it('closes the ring for a finished polygon draft', () => {
    const draft: GeofenceDraft = {
      shape: 'POLYGON',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    };

    const feature = draftToFeature(draft);

    const ring = feature?.geometry.coordinates[0];
    expect(ring?.[0]).toEqual(ring?.[ring.length - 1]);
    expect(ring).toHaveLength(4);
  });

  it('returns undefined for a circle draft with no radius yet', () => {
    const draft: GeofenceDraft = { shape: 'CIRCLE', center: { lat: 1, lon: 1 }, radiusMeters: 0 };
    expect(draftToFeature(draft)).toBeUndefined();
  });

  it('builds a closed polygon approximation for a committed circle draft', () => {
    const draft: GeofenceDraft = { shape: 'CIRCLE', center: { lat: 1, lon: 1 }, radiusMeters: 100 };

    const feature = draftToFeature(draft);

    const ring = feature?.geometry.coordinates[0];
    expect(ring?.[0]).toEqual(ring?.[ring.length - 1]);
    expect(ring?.length).toBeGreaterThan(3);
  });
});

describe('draftVerticesToFeatureCollection', () => {
  it('returns each polygon draft vertex as its own Point feature', () => {
    const draft: GeofenceDraft = { shape: 'POLYGON', vertices: [{ lat: 1, lon: 2 }, { lat: 3, lon: 4 }] };

    const collection = draftVerticesToFeatureCollection(draft);

    expect(collection.features).toHaveLength(2);
    expect(collection.features[0]?.geometry).toEqual({ type: 'Point', coordinates: [2, 1] });
  });

  it('returns an empty collection for a circle draft or no draft', () => {
    expect(draftVerticesToFeatureCollection(undefined).features).toEqual([]);
    expect(draftVerticesToFeatureCollection({ shape: 'CIRCLE', center: { lat: 1, lon: 1 }, radiusMeters: 10 }).features).toEqual([]);
  });
});
