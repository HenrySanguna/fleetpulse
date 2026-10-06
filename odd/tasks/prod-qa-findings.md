# Production QA findings (2026-09-24 full prod test)

## Objective
Fix the defects found in the full Playwright pass against production (`4db90ba`).

## Why
Alert inbox flooded by boundary re-entries, inconsistent activity metrics, unreadable track, unusable mobile layout, raw numbers, mixed UI language.

## Scope / decisions (defaults chosen by orchestrator, user delegated: "resolve as you see fit")
- Geofence alerts: add a per (vehicle, geofence, alert type) silence window (default 10 min, configurable), same pattern as `AlertSilenceEngine`. Keep existing hysteresis.
- Activity report: surface the in-progress trip so listed trips reconcile with the summary.
- Track: console requests a bounded window (last 2 h) and splits the line at implausible jumps; distinct track color + legend entry.
- UI copy: unify to Spanish (no i18n library in repo; Transloco adoption is a separate follow-up).
- ETA: display-only fix (backend math is correct near destination).
- Alert ack audit: store and show `acknowledged_at` / `acknowledged_by`.
- Out of scope: logout mixed-content redirect (backend proxy config), simulator teleports at source.

## TDD
Off (no project directive). Checks: `npx nx test console`, `npx nx lint console`, `npx nx build console --configuration=production`, `npx nx test console-ui`, backend `./gradlew :<module>:test` (Testcontainers, needs Docker), `api-client:check-drift`.

## Tasks
- [x] T1 Router wildcard route (no NG04002 on unknown URL)
- [x] T2 Live-map geolocation fallback fits fleet when vehicles arrive after denial
- [x] T3 Geofence editor: read-only form for dispatchers; fit map to selected geofence
- [x] T4 Number/date formatting in vehicle detail + activity page; ETA display < 1 min
- [x] T5 Track: bounded window, split at jumps, distinct color + legend (79fc809 + review fix 7f205fa, PR #80 on fix/console-qa-track, delegated writer + inline fix; console 255/255, lint ok, prod build ok; review lineage review-40eb5ac70c288daa approved+acknowledged; R3-001 gap rule removed, R3-002 50 m jitter tolerance; R3-003 window frozen while same vehicle stays selected = follow-up; ~600 lines, size:exception noted)
- [x] T6 Responsive layout (<768px): collapsible rail, stacked live-map panel (47ee99b on fix/console-qa-responsive, delegated writer; CDK BreakpointObserver + p-drawer, CSS stacking, MapLibre ResizeObserver handles resize; console 259/259, lint ok, prod build ok 603.01 kB warn; review lineage review-d754b44e7dbd4f84 (slice 79fc809..47ee99b incl. 7f205fa) approved+acknowledged; warning drawer re-open fixed e650298 with regression spec (verified failing without fix); console 261/261; PR #81)
- [x] T7 Unify UI copy to Spanish + consistent "atendida" terminology (8967c55, PR #82 on fix/console-qa-spanish-copy, delegated writer; console 261/261, console-ui 21/21, lint ok, build ok; assess base 47ee99b: medium 251 lines under_budget, pending in slice with e650298)
- [x] T8 Geofence alert silence window (processor) (8acd5ef on fix/processor-geofence-alert-silence from origin/main, delegated writer; no migration; `fleetpulse.geofencing.silence-window` default 10m; event semantics: first fires, repeats within window suppressed; review lineage review-c8ce8c10feeba128 approved+acknowledged; findings fixed in c2d1ddb (per-message silence persistence, only mutated keys, awaited positive signals, in-batch e2e) -- fix writer hit rate limit, parent reviewed staged diff and committed; :processor:check 166/0 failures; PR #83 to main; c2d1ddb 168 lines under budget, unreviewed)
- [x] T9 Alert acknowledge audit (V14 migration, API, client regen, UI) (8561d0f api+client, 75b2e9a console on fix/alert-ack-audit from #82, delegated writer; Co-Authored-By trailers stripped by parent; V14 acknowledged_at + acknowledged_by UUID FK users ON DELETE SET NULL, DTO exposes email; idempotent keeps first ack; :api:test :domain:test ok, check-drift ok, console 265/265; review lineage review-231eea9b78d5fd29 approved+acknowledged; suggestion applied 8abe07d; PR #84)
- [x] T10 Activity report shows in-progress trip (bf1fc1b api+client, 736b96e console on fix/activity-in-progress-trip from #84, delegated writer; separate nullable `inProgressTrip` DTO; mirrors TripSegmenter state machine + motion thresholds in api (manual-sync liability, follow-up); gate by UTC day; :api:check ok, check-drift ok, console 269/269; review lineage review-5940f7c3bb00e3ae approved+acknowledged; warning (JVM-zone today in tests) fixed 73b9bc1; split into PR #85 api (b7270fd+42f3c1d, ~660 lines size:exception) and PR #86 console (b8d1ad1); original branch fix/activity-in-progress-trip superseded)

## Routes
Mapping: delegated (Explore, 10 findings across front/back). Implementation: delegated writer per task group (2+ non-trivial files each).

## Delivery
Forecast > 400 authored lines -> chained PRs. User (2026-09-25): commits + PRs automatic; strategy auto-chain, chain `stacked-to-main` (each PR based on previous branch).
- #78 `fix/console-qa-routing-geofence` (base main): T1 fce71f4, T2 16daf79, T3 ef37497, R3 fix 86792d0 (cherry-pick of 56c51c9). 354 lines.
- #79 `fix/console-qa-formatting` (base #78): T4 6fee583 (cherry-pick of 6a264ba). 118 lines.
- Original local branch `fix/prod-qa-findings` superseded (same tree as #79 head).
- #80 `fix/console-qa-track` (base #79): T5 79fc809 + 7f205fa.
- #81 `fix/console-qa-responsive` (base #80): T6 47ee99b + e650298.
- #82 `fix/console-qa-spanish-copy` (base #81): T7 8967c55.
- #84 `fix/alert-ack-audit` (base #82): T9 8561d0f + 75b2e9a + 8abe07d.
- #85 `fix/api-activity-in-progress-trip` (base #84): T10 api. #86 `fix/console-activity-in-progress-trip` (base #85): T10 console.
- #83 `fix/processor-geofence-alert-silence` (base main, independent): T8 8acd5ef + c2d1ddb. T9/T10 stack on #82 (touch alerts/activity pages).

## Progress / evidence

### T1 Router wildcard route
- Commit: fce71f4 "fix(console): add wildcard route to prevent NG04002 on unknown URLs"
- Files: `apps/console/src/app/app.routes.ts` (added `{ path: '**', redirectTo: '' }` at the end of the top-level array, inside the guarded shell's redirect target so `authGuard` still runs), `apps/console/src/app/app.routes.spec.ts` (new minimal spec — no `app.routes.spec.ts` existed before; kept it lightweight, just asserting the wildcard entry, per "don't invent heavy infra").
- Checks: `npx nx test console` -> 28 files / 223 tests passed. `npx nx lint console` -> all files pass.

### T2 Live-map geolocation fallback
- Commit: 16daf79 "fix(console): wait for fleet positions before applying geolocation fallback view"
- Files: `apps/console/src/app/features/live-map/components/live-map.component.ts` (`centerOnFleetOrDefault` now returns `boolean`; the centering effect only sets `initialCenterApplied` when a point was used or the fleet fit actually happened, so it keeps re-running — via its existing read of `fleetStore.vehicles()` — until a position arrives), `apps/console/src/app/features/live-map/components/live-map.component.spec.ts` (kept the existing "no vehicles known -> default view" spec unchanged; added "fits the fleet bounds once when vehicle positions arrive after a denied/unavailable geolocation" and "does not fit the fleet bounds if the user drags before any vehicle position arrives").
- Checks: `npx nx test console` -> 28 files / 225 tests passed (2 new). `npx nx lint console` -> all files pass.

### T3 Geofence editor: dispatcher read-only + fit-to-selection
- Commit: ef37497 "fix(console): make geofence form read-only for dispatchers and fit map to selection"
- Files: `apps/console/src/app/features/geofencing/pages/geofence-editor-page.component.ts` (new constructor `effect()` calling `form.enable()`/`form.disable()` off `canManageGeofences()`; canSave's existing `!canManageGeofences()` short-circuit still governs the button, unaffected by disable's own statusChanges emission), `.html` (added `[selectedGeofence]="selected()"` binding to the drawing editor), `.spec.ts` (added enabled/disabled assertions for admin/dispatcher plus a wiring test); `apps/console/src/app/features/geofencing/components/geofence-drawing-editor.component.ts` (new `selectedGeofence` input + effect fitting the map to `geofenceBounds()` when idle, unconditionally reading `mode()` so it re-fires once drawing ends), `.spec.ts` (added `fitBounds` to the fake map + 2 tests); `apps/console/src/app/features/geofencing/services/geofence-geometry.util.ts` (new exported `geofenceBounds()` — reused for both shapes since `GeofenceResponse.vertices` is always the buffered polygon, per the file's existing documented deviation), `.spec.ts` (4 new cases).
- Deviation: task wording mentions "circle center+radius bounds" as a separate case, but `GeofenceResponse` (libs/api-client) never exposes center/radius — only `vertices`, always the buffered polygon (see `openRing`'s existing doc comment and `buildUpdateRequest`'s documented deviation in the page component). `geofenceBounds()` computes a min/max lon/lat box over `vertices` uniformly, which covers both shapes correctly without a separate branch.
- Checks: `npx nx test console` -> 28 files / 234 tests passed (9 new). `npx nx lint console` -> all files pass.

### T4 Number/date formatting + ETA display
- Commit: 6a264ba "fix(console): format raw numbers and dates in vehicle detail and activity report"
- Files: `libs/console-ui/src/lib/vehicle-detail/vehicle-detail.component.ts` (`formatEtaLabel` now renders "< 1 min" instead of "~0 min" when eta or margin rounds to zero; added `DecimalPipe`/`DatePipe` to imports), `.html` (lat/lon via `number:'1.5-5'`, speed/heading via `number:'1.0-0'`, `recordedAt` via `date:'dd/MM/yyyy HH:mm:ss'` -- no `LOCALE_ID` is registered anywhere in the app, confirmed via grep, so en-US default applies and an explicit format string was used per the task's own guidance), `.spec.ts` (updated the position assertion, added a formatting test and an eta-rounds-to-zero test, both at the component and the pure-function level); `apps/console/src/app/features/activity-report/pages/activity-report-page.component.ts` (new `formatSpeedKmh()` used by `speedLabel` and a new `formatTripMaxSpeed()`), `.html` (trip row's Vel. máx. cell now calls `formatTripMaxSpeed(trip)`), `.spec.ts` (VH-1042's mock `avgSpeedKmh`/one trip's `maxSpeedKmh` changed to decimals matching the actual prod bug value, `44.36987553618879`, plus a new rounding test); `apps/console/src/app/features/live-map/pages/live-map-page.component.spec.ts` (updated one assertion from `'5, 6'` to `'5.00000, 6.00000'` -- pre-existing regression caused by the lat/lon formatting change).
- Other raw-number spots checked and left alone since already formatted consistently: chart bar / trip-row distance already use `.toFixed(1)`, and moving/idle time already go through `formatHoursMinutes()`.
- Checks: `npx nx test console` -> 28 files / 235 tests passed. `npx nx test console-ui` -> 4 files / 21 tests passed. `npx nx lint console` -> all files pass. `npx nx lint console-ui` -> all files pass. `npx nx build console --configuration=production` -> succeeded (602.25 kB initial bundle; pre-existing `maximumWarning: 600kb` budget in `apps/console/project.json` triggers a non-blocking warning, exceeded by 2.25 kB -- build still reports success, `maximumError` is 1mb).

## Verification summary (T1-T4)
- `npx nx test console` -> 28 test files, 235 tests passed.
- `npx nx test console-ui` -> 4 test files, 21 tests passed.
- `npx nx lint console` -> all files pass.
- `npx nx lint console-ui` -> all files pass.
- `npx nx build console --configuration=production` -> succeeded, with a pre-existing non-blocking initial-bundle budget warning (602.25 kB vs. the 600kB warning threshold; error threshold is 1mb).
- T5-T10 remain untouched (out of this writer's scope).

### Session end 2026-09-25
- Parent spot check: `npx nx test console` re-run -> 28 files / 235 tests passed. No AI attribution in commit messages. Branch 18 files, +388/-23, local only (not pushed).
- Native review: `gentle-ai review assess --base-ref main --committed-only` -> review_due=true (high: unassessable due to untracked `odd/`; rerun with `--untracked-scope=exclude`). Pending: candidate consent needs the user.
- Build note: prod bundle 602.25 kB vs 600 kB warning budget (warning only).

### Native review T1-T4 (2026-09-25)
- Consent granted. Lineage review-b9aba66b61aee1d5, medium risk, lens review-reliability, base 515518d..6a264ba (untracked `odd/` excluded). Approved + acknowledged (authority burned). Reviewed boundary advances to 6a264ba.
- Advisory (non-blocking) follow-ups: R3-fit-refire (WARNING: drawing-editor fit re-fires on mode->idle or new selected() reference, overriding user pan/zoom); R3-form-enable-all (form.enable() re-enables all controls); R3-route-structural-test (wildcard spec is structural, not a Router navigation); R3-bounds-antimeridian (min/max lon breaks across antimeridian).

### R3-fit-refire fix (inline, 2 files, one understood change)
- Commit: 56c51c9 "fix(console): fit geofence editor map once per selected geofence". Effect keeps `lastFittedGeofenceKey` (id, fallback reference); fits only when key changes, resets on deselect. 4 new specs (mid-drawing selection fits after cancel; no refit after cancel; no refit on new reference; refit on different id).
- Checks: `npx nx test console` -> 28 files / 239 tests passed. `npx nx lint console` -> pass.
- Review assess (base 6a264ba): medium, 61 lines, review_due=false (under_budget) -> pending in slice.

### Backend map for T8-T10 (delegated Explore, 2026-09-25)
- Modules: backend api, processor, domain, geo-core; tests `backend/gradlew.bat :<module>:test` (Testcontainers in api/domain/processor). Docker confirmed running (29.7.2).
- T8: flood source `processor/.../geofencing/GeofenceRuleDispatcher.java` `dispatchForGeofence` (~126-167) writes fired alerts with no silence check. Reuse `alerts/AlertSilenceEngine` + `AlertSilenceKey(vehicleId, type, context=geofenceId)` + `JdbcAlertSilenceStateStore` (table `alert_silence_state`, V12), pattern in `AlertRuleDispatcher.evaluateRule` (115-140). Config: new `silenceWindow` @DefaultValue("10m") on `FleetpulseGeofencingProperties`. Tests to model: `AlertSilenceEngineTest`, `AlertRuleEndToEndTest`, geofencing e2e tests.
- T9: `alerts` table has only `acknowledged`; next migration V14. `AlertsController` PATCH `/api/alerts/{id}/acknowledge` (62-66) -> `AlertsService.acknowledge` -> `JdbcAlertsRepository` (UPDATE 45-47, SELECT cols 35-41, toResponse 102-115); `AlertResponse` DTO; identity via `CurrentDispatcher.require()`. Client regen `nx run api-client:generate` (boots api bootJar, springdoc), CI `api-client:check-drift`. Console: `features/alerts/models/alert.model.ts`, `services/alerts.service.ts`, `alerts.store.ts`, `pages/alerts-page.component.html` (105-115).
- T10: summary from `vehicle_daily` (includes open trip); list from `trips` (only closed; `TripSegmenter` never emits trailing span, 47-55/102-105). Decision (scope): surface in-progress trip (display-only) so list reconciles with summary.

## Next step
All T1-T10 delivered as PRs #78-#86 (2026-09-25). Merge order: #78 -> #79 -> #80 -> #81 -> #82 -> #84 -> #85 -> #86; #83 independent.
Follow-ups: R3-003 track window frozen while same vehicle selected; R3-form-enable-all; R3-route-structural-test; R3-bounds-antimeridian; T10 mirrored TripSegmenter rules in api (extract shared module); service spec for toInProgressTrip; injectable Clock in ActivityReportService; unreviewed under-budget commits e650298+8967c55, c2d1ddb, 8abe07d; Transloco adoption; logout mixed-content redirect.
