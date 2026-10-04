import { defaultActivityReportRange, toActivityReportRange } from './activity-report-range';

describe('defaultActivityReportRange', () => {
  it('spans exactly the last 7 calendar days (today plus the 6 days before it)', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 17, 12, 0, 0));

    const range = defaultActivityReportRange();

    expect(range.to.getDate()).toBe(17);
    expect(range.from.getDate()).toBe(11);
    expect(range.from.getMonth()).toBe(range.to.getMonth());

    vi.useRealTimers();
  });

  it('always returns a from strictly before to', () => {
    const range = defaultActivityReportRange();

    expect(range.from.getTime()).toBeLessThan(range.to.getTime());
  });
});

describe('toActivityReportRange', () => {
  it('spans from the start of the first day to the end of the last day for a past range', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 17, 12, 0, 0));

    const range = toActivityReportRange(new Date(2026, 8, 1, 15, 30), new Date(2026, 8, 5, 3, 0));

    expect(range.from).toEqual(new Date(2026, 8, 1, 0, 0, 0, 0));
    expect(range.to).toEqual(new Date(2026, 8, 5, 23, 59, 59, 999));

    vi.useRealTimers();
  });

  it('clamps the end to now when the last day is today', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 17, 12, 0, 0));

    const range = toActivityReportRange(new Date(2026, 8, 11), new Date(2026, 8, 17));

    expect(range.to).toEqual(new Date(2026, 8, 17, 12, 0, 0));

    vi.useRealTimers();
  });

  it('treats a single picked day as a one-day range', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 17, 12, 0, 0));

    const range = toActivityReportRange(new Date(2026, 8, 3), null);

    expect(range.from).toEqual(new Date(2026, 8, 3, 0, 0, 0, 0));
    expect(range.to).toEqual(new Date(2026, 8, 3, 23, 59, 59, 999));

    vi.useRealTimers();
  });
});
