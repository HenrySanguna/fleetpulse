import type { TrackPointResponse } from '@fleetpulse/api-client';
import { splitTrackIntoSegments, toTrackSegmentFeatures } from './vehicle-track.util';

describe('splitTrackIntoSegments', () => {
  it('returns no segments for no points, and drops points missing lat/lon', () => {
    expect(splitTrackIntoSegments([])).toEqual([]);
    expect(
      splitTrackIntoSegments([
        { lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' },
        { lat: undefined, lon: undefined, recordedAt: '2026-01-01T00:00:10Z' },
        { lat: 1.001, lon: 2.001, recordedAt: '2026-01-01T00:00:20Z' },
      ]),
    ).toEqual([
      [
        { lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' },
        { lat: 1.001, lon: 2.001, recordedAt: '2026-01-01T00:00:20Z' },
      ],
    ]);
  });

  it('keeps consecutive points in one segment when the implied speed and time gap are both plausible', () => {
    // ~0.11 km apart, 10s later -> well under 250 km/h.
    const points: TrackPointResponse[] = [
      { lat: 0, lon: 0, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: 0.001, lon: 0, recordedAt: '2026-01-01T00:00:10Z' },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([points]);
  });

  // Decided scope: implausible jump = implied speed > 250 km/h.
  it('splits into a new segment when the implied speed exceeds 250 km/h', () => {
    const points: TrackPointResponse[] = [
      { lat: 0, lon: 0, recordedAt: '2026-01-01T00:00:00Z' },
      // 1 degree of longitude at the equator is ~111 km, covered in 60s -> ~6660 km/h.
      { lat: 0, lon: 1, recordedAt: '2026-01-01T00:01:00Z' },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([[points[0]], [points[1]]]);
  });

  // Decided scope: implausible jump = time gap > 5 min, even with no distance.
  it('splits into a new segment when the time gap exceeds 5 minutes, regardless of distance', () => {
    const points: TrackPointResponse[] = [
      { lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: 10, lon: 20, recordedAt: '2026-01-01T00:06:00Z' },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([[points[0]], [points[1]]]);
  });

  it('splits on a simulator teleport sharing the previous timestamp (zero elapsed time, real movement)', () => {
    const points: TrackPointResponse[] = [
      { lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: 10, lon: 21, recordedAt: '2026-01-01T00:00:00Z' },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([[points[0]], [points[1]]]);
  });

  it('never splits on unparseable or missing timestamps', () => {
    const points: TrackPointResponse[] = [
      { lat: 10, lon: 20, recordedAt: 'not-a-date' },
      { lat: 40, lon: 50, recordedAt: undefined },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([points]);
  });

  it('carries a jump across a dropped invalid point in between', () => {
    const points: TrackPointResponse[] = [
      { lat: 10, lon: 20, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: undefined, lon: undefined, recordedAt: '2026-01-01T00:00:05Z' },
      { lat: 10, lon: 20, recordedAt: '2026-01-01T00:10:00Z' },
    ];

    expect(splitTrackIntoSegments(points)).toEqual([[points[0]], [points[2]]]);
  });
});

describe('toTrackSegmentFeatures', () => {
  it('returns no features for no points or a single point (no line to draw)', () => {
    expect(toTrackSegmentFeatures(undefined)).toEqual([]);
    expect(toTrackSegmentFeatures([])).toEqual([]);
    expect(toTrackSegmentFeatures([{ lat: 1, lon: 2, recordedAt: 'a' }])).toEqual([]);
  });

  it('maps a plausible run of points to a single LineString feature in [lon, lat] GeoJSON order', () => {
    const points: TrackPointResponse[] = [
      { lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: 1.001, lon: 2.001, recordedAt: '2026-01-01T00:00:10Z' },
    ];

    expect(toTrackSegmentFeatures(points)).toEqual([
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [2, 1],
            [2.001, 1.001],
          ],
        },
        properties: {},
      },
    ]);
  });

  it('emits one feature per plausible run and drops runs too short to draw a line', () => {
    const points: TrackPointResponse[] = [
      { lat: 0, lon: 0, recordedAt: '2026-01-01T00:00:00Z' },
      { lat: 0.001, lon: 0, recordedAt: '2026-01-01T00:00:10Z' },
      // > 5 min gap -> new segment, but it is the only point in it (dropped).
      { lat: 50, lon: 50, recordedAt: '2026-01-01T01:00:00Z' },
      { lat: 50.001, lon: 50, recordedAt: '2026-01-01T01:00:10Z' },
    ];

    expect(toTrackSegmentFeatures(points)).toEqual([
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [0, 0],
            [0, 0.001],
          ],
        },
        properties: {},
      },
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [50, 50],
            [50, 50.001],
          ],
        },
        properties: {},
      },
    ]);
  });
});
