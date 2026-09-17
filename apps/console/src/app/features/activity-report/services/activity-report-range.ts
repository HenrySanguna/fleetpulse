// Task 4.3: the console's date-range control stays presentation-only (see
// ActivityReportPageComponent's own doc comment -- no interactive picker is
// in scope here), but the real backend endpoint still needs a concrete
// from/to range to query. This is the one default range both
// ActivityReportStore's HTTP request and the page's own display label
// share, so they can never silently drift into two different "last 7 days"
// definitions.
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
