import { isEffectivelyOnline, ONLINE_STALENESS_MS } from './vehicle-online.util';

describe('isEffectivelyOnline', () => {
  const now = Date.parse('2026-10-04T12:00:00Z');
  const ago = (ms: number): string => new Date(now - ms).toISOString();

  it('is online when flagged online and the last update is recent', () => {
    expect(isEffectivelyOnline({ online: true, recordedAt: ago(ONLINE_STALENESS_MS - 1000) }, now)).toBe(true);
  });

  it('is offline once the last update is exactly at or past the threshold', () => {
    expect(isEffectivelyOnline({ online: true, recordedAt: ago(ONLINE_STALENESS_MS) }, now)).toBe(false);
    expect(isEffectivelyOnline({ online: true, recordedAt: ago(ONLINE_STALENESS_MS + 1000) }, now)).toBe(false);
  });

  it('is offline when flagged offline even if the update is recent', () => {
    expect(isEffectivelyOnline({ online: false, recordedAt: ago(1000) }, now)).toBe(false);
  });

  it('is offline without a flag, a timestamp, or with an unparsable timestamp', () => {
    expect(isEffectivelyOnline({ recordedAt: ago(1000) }, now)).toBe(false);
    expect(isEffectivelyOnline({ online: true }, now)).toBe(false);
    expect(isEffectivelyOnline({ online: true, recordedAt: 'garbage' }, now)).toBe(false);
  });
});
