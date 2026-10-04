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

// A date picker yields calendar days at local midnight, but the backend maps
// from/to to UTC calendar days, so the picked day numbers become UTC day
// bounds (start of the first, end of the last). Local-midnight instants would
// pull in the previous UTC day for browsers east of UTC. The end is clamped to
// "now" so a range ending today never asks for the future, and the start never
// passes it. A single picked day is a one-day range.
export function toActivityReportRange(firstDay: Date, lastDay: Date | null): ActivityReportRange {
  const last = lastDay ?? firstDay;
  const now = new Date();
  const endOfLastDay = new Date(Date.UTC(last.getFullYear(), last.getMonth(), last.getDate(), 23, 59, 59, 999));
  const to = endOfLastDay.getTime() > now.getTime() ? now : endOfLastDay;
  const startOfFirstDay = new Date(Date.UTC(firstDay.getFullYear(), firstDay.getMonth(), firstDay.getDate()));
  const startOfToDay = new Date(Date.UTC(to.getUTCFullYear(), to.getUTCMonth(), to.getUTCDate()));
  return { from: startOfFirstDay.getTime() > to.getTime() ? startOfToDay : startOfFirstDay, to };
}
