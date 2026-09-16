import { mapInboundMessage } from './fleet-message.mapper';

describe('mapInboundMessage', () => {
  it('maps a telemetry message, taking the vehicleId from the topic', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v1/telemetry',
      payload: { recordedAt: '2026-01-01T00:00:00Z', lat: 10, lon: 20, speedKmh: 42, heading: 90 },
    });

    expect(update).toEqual({
      kind: 'telemetry',
      vehicleId: 'v1',
      recordedAt: '2026-01-01T00:00:00Z',
      lat: 10,
      lon: 20,
      speedKmh: 42,
      heading: 90,
    });
  });

  it('maps a telemetry message without optional speedKmh/heading', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v1/telemetry',
      payload: { recordedAt: '2026-01-01T00:00:00Z', lat: 10, lon: 20 },
    });

    expect(update).toEqual({
      kind: 'telemetry',
      vehicleId: 'v1',
      recordedAt: '2026-01-01T00:00:00Z',
      lat: 10,
      lon: 20,
      speedKmh: undefined,
      heading: undefined,
    });
  });

  it('discards a telemetry message missing required fields', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v1/telemetry',
      payload: { recordedAt: '2026-01-01T00:00:00Z', lat: 10 },
    });

    expect(update).toBeUndefined();
  });

  it('maps a presence message, taking the vehicleId from the topic', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v2/status',
      payload: { online: true },
    });

    expect(update).toEqual({ kind: 'presence', vehicleId: 'v2', online: true });
  });

  it('discards a presence message with a non-boolean online field', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v2/status',
      payload: { online: 'yes' },
    });

    expect(update).toBeUndefined();
  });

  it('maps an eta message, taking the vehicleId from the topic', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v3/eta',
      payload: { etaSeconds: 900, etaMarginSeconds: 270, calculatedAt: '2026-01-01T00:00:05Z' },
    });

    expect(update).toEqual({
      kind: 'eta',
      vehicleId: 'v3',
      etaSeconds: 900,
      etaMarginSeconds: 270,
      calculatedAt: '2026-01-01T00:00:05Z',
    });
  });

  it('discards an eta message missing required fields', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v3/eta',
      payload: { etaSeconds: 900 },
    });

    expect(update).toBeUndefined();
  });

  it('discards a message whose payload failed to parse as JSON', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v1/telemetry',
      payload: undefined,
    });

    expect(update).toBeUndefined();
  });

  it('discards a message on an unrecognized topic', () => {
    const update = mapInboundMessage({
      topic: 'fleet/org-1/vehicle/v1/unknown',
      payload: { online: true },
    });

    expect(update).toBeUndefined();
  });
});
