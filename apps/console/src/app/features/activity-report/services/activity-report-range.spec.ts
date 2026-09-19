import { defaultActivityReportRange } from './activity-report-range';

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
