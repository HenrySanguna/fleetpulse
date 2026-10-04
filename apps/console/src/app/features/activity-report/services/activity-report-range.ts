// The backend endpoint needs a concrete from/to range to query. This is the
// default (last 7 days) the store requests until the user picks another one,
// and the value the page's date picker starts from.
export interface ActivityReportRange {
  readonly from: Date;
  readonly to: Date;
}

export function defaultActivityReportRange(): ActivityReportRange {
  const to = new Date();
  const from = new Date(to);
  from.setDate(to.getDate() - 6);
  return { from, to };
}

// A date picker yields calendar days at local midnight. The range covers
// whole days (start of the first, end of the last), with the end clamped to
// "now" so a range ending today never asks for the future. A single picked
// day is a one-day range.
export function toActivityReportRange(firstDay: Date, lastDay: Date | null): ActivityReportRange {
  const from = new Date(firstDay);
  from.setHours(0, 0, 0, 0);
  const endOfLastDay = new Date(lastDay ?? firstDay);
  endOfLastDay.setHours(23, 59, 59, 999);
  const now = new Date();
  return { from, to: endOfLastDay.getTime() > now.getTime() ? now : endOfLastDay };
}
