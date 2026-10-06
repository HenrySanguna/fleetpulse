# Prod QA pass 2 (2026-10-04): fixes for high/medium findings

## Objective
Fix the high and medium defects found in the 2026-10-04 Playwright pass against production. Mobile layout issues are explicitly out of scope (user decision).

## Why
- Vehicles show "En línea" with 3-day-old data (no staleness detector; the demo simulator stops with a clean MQTT disconnect, so no Last Will fires).
- `/activity` summary does not reconcile with trips: `JdbcDailyRollupWriter` overwrites each day with the SUM of only the trailing 24 h window. Confirmed with prod data: `vehicle_daily` = last hourly row only (14.7 km) while `vehicle_hourly` sums 382 km and trips 352.5 km.
- Console gaps: no geofence delete, alerts header says "últimas 24 horas" over all-time data, activity date range not selectable.
- Neon idle commits (4401a96, 6422c4c, 8703008, d4f97f9) were checked and are NOT the cause; the idle gate only freezes the wrong daily value.

## Scope / decisions (user)
- Offline threshold: 5 minutes (`online && recordedAt > now - 5 min`), derived (no job, no extra DB polling, Neon-friendly).
- Geofence delete: button visible only to FLEET_ADMIN (`canManageGeofences()`), with confirmation. Backend `DELETE /api/geofences/{id}` already exists (soft delete).
- Out of scope: mobile layout (D7/D8), UUID display (D9), formatting nits (D5, D11, D12), nested deep-link chunk MIME error (D10).
- Push, PR creation and merge stay with the user. Local commits only.

## TDD
Applicable where a runnable deterministic test exists. Backend tests are Testcontainers (need Docker; `docker info` returned nothing at planning time: if Docker is unavailable, report it, never invent results). Console: Vitest. Checks: `./gradlew :processor:test :api:test` (from `backend/`), `npx nx test console`, `npx nx lint console`, `npx nx build console --configuration=production`, `api-client:check-drift` if the contract changes.

## Tasks
- [x] T1 Daily rollup recompute whole days + backfill migration V15 (processor/domain) — branch `fix/rollup-daily-recompute` from origin/main. Committed 3cf80f9 (delegated writer; parent read the diff: SQL range starts at UTC day start of windowStart; V15 backfill from vehicle_hourly). Writer could not run tests (Docker down); parent verified once Docker was up: `:processor:test --tests '*JdbcDailyRollupWriterTest' --tests '*VehicleRollup*'` BUILD SUCCESSFUL; RED with the fix reverted: 2 of 4 new tests fail (slidingWindowDoesNotOverwriteADayWithOnlyItsTrailingHours, windowStartingMidDayRecomputesTheWholeDay), fix restored. Not run: full `:processor:test` / `:domain:test`; V15 executes through the Flyway migration in these Testcontainers tests only.
- [x] T2 Derive `online` with a 5-min staleness threshold (api snapshot + console badge). Branch `fix/stale-online-alerts-header`. 23669dd api (`FleetStateService` + `Clock` bean + `FleetpulseFleetStateProperties` 5m default; no contract change), 3e17a10 console (`vehicle-online.util.ts` + 30 s ticking `nowMs` in `FleetStore`, covers list/detail/filters/map without touching them). Delegated writer. Checks: `:api:test` BUILD SUCCESSFUL (Docker up), console 285/285, console-ui 21/21, lint ok, prod build ok. Behaviour change: `online=true` without `recordedAt` now reads offline. Not verified: real-browser flip (fake-timer specs only). Parent spot check: commits/stat read, no AI attribution, console-ui test green (nx cache hit).
- [x] T3 Alerts header: decision = drop the "24 horas" claim instead of filtering (unattended alerts must not vanish after a day). 7b97da9, RED observed then GREEN.
- [x] T4 Activity report date-range picker. Branch `fix/activity-range-geofence-delete` (stacked on B1). 35fb3c8 (PrimeNG range DatePicker, `range` state in store, Aura tokens datepicker/confirmdialog/dialog + Spanish locale in app.config.ts). Backend enforces no max span nor from<=to (only ISO parse), so client enforces only no-future/required/whole days; a very long range is uncapped (follow-up if slow).
- [x] T5 Geofence delete (admin only, with confirmation). 64c51eb (`deleteVoid` helper, `GeofenceService.delete`, `removeGeofence`, "Eliminar geocerca" button gated by `canManageGeofences()` + saved selection, ConfirmDialog). RED then GREEN for both. Parent spot check: `npx nx test console` 32 files / 305 tests passed; lint + prod build reported ok by writer. Not verified: real-browser rendering of DatePicker/ConfirmDialog styling.
- [ ] T6 Ship the unmerged console stack (#86 + #88, branch `fix/console-activity-in-progress-trip`, 537 lines not in main, includes the inProgressTrip UI): user decision, PR to main

## Routes
Mapping: delegated (2 explorers, 10+ findings). Implementation: one delegated writer per task (2+ non-trivial files each); writes single-threaded.

## Delivery
Forecast ~850 authored lines > 400 -> sliced. Strategy reused from 2026-09-25 user choice: auto-chain, `stacked-to-main`.
- Slice A `fix/rollup-daily-recompute` (base origin/main): T1 (~150 lines).
- Slice B1 `fix/stale-online-alerts-header` (base `origin/fix/console-activity-in-progress-trip`, because the console stack is not in main): T2 + T3.
- Slice B2 `fix/activity-range-geofence-delete` (base B1): T4 + T5.
- T6 needs the user: the base branch holds PRs #86/#88 never merged to main.

## Progress / evidence
(2026-10-04) Doc created. Branch `fix/rollup-daily-recompute` created from origin/main (33b2a1f).

## Delivery state (2026-10-04, local only, nothing pushed)
- A `fix/rollup-daily-recompute`: 3cf80f9 (~181 lines).
- B1 `fix/stale-online-alerts-header` (base origin/fix/console-activity-in-progress-trip): 23669dd, 3e17a10, 7b97da9.
- B2 `fix/activity-range-geofence-delete` (base B1): 35fb3c8, 64c51eb. B1+B2 diff vs the #86/#88 base: 29 files, +749/-84 (size exception if sent as one PR).
- Review assessment (`gentle-ai review assess`) not run: user-owned RDD switch state not queried this session.

## PRs (opened by user request, all base `main`, 2026-10-04)
- #94 `fix/console-activity-in-progress-trip` (the #86/#88 console stack that never reached main, 17 files +537/-24)
- #95 `fix/rollup-daily-recompute` (A, independent)
- #96 `fix/stale-online-alerts-header` (B1; diff includes #94 commits until it merges)
- #97 `fix/activity-range-geofence-delete` (B2; includes #94 + #96 commits until they merge)
All target main directly (not stacked bases) to avoid repeating the #86/#88 stranding. Merge order: #94, #96, #97; #95 any time. Merges and deploy remain the user's.

## Local verification (2026-10-04, delegated, local stack only)
- Integration branch (B2 + rollup branch) : `nx affected -t test build lint` ok (console 305), backend `./gradlew test` + `build` ok (api 119, domain 48, geo-core 79, processor 182, 0 failures). Browser pass as admin + dispatcher: DatePicker styled/Spanish/no future days, geofence delete (204, soft delete, cancel keeps it), dispatcher no button and DELETE 403, alerts header, real offline flip (>5 min, no reload), rollup daily == hourly sum, V15 repaired a corrupted row.
- Found and FIXED in PR #97: 70c9a0b date range sent local-midnight instants but backend maps from/to to UTC days (UTC+2 picking 2 Oct included 1 Oct). RED in Europe/Madrid, GREEN in Madrid/UTC/Bogota/Auckland; console 306/306, lint, build ok.
- Pre-existing, NOT fixed: e2e specs expect "Connected" (UI is "Conectado" since 8967c55; 3/3 chromium fail on main too); `InProgressTripCalculator.java:80` clips startedAt to range `from` when there is no prior closed trip, so the card can show a fabricated start/duration (e.g. 143h 59m); `nx serve` cannot render the map (maplibre worker 404).
- Not tested: #94 track window refresh, closed trips vs daily total (simulator ran ~10 min), firefox/webkit e2e (browsers not installed).

## Outcome (2026-10-06)
- [x] T6: #94, #96, #97 merged to main by the user; #95 merged after its CI re-run passed (first run failed once on `ActivityReportEndpointTest` line 240, `startedAt` of the in-progress trip; passed locally with the same code and on re-run, so treated as intermittent; actual-vs-expected values were never seen because the CI log does not print them).
- Local branches already merged were deleted; `.playwright-mcp/` is now gitignored.

## Next step
After deploy: re-run the Playwright pass; delete the leftover prod geofence `QA-TEST-1759579200-edited` (id 8802361f-9591-4699-80ae-51e253e14a3e) with the new admin button; validate T1/V15 backfill against prod `vehicle_daily` (vehicle `8f1cbe99-3d34-49bb-962e-981d28ba4dce`, 2026-10-01 should be about 382 km).
Open follow-ups (not started): `InProgressTripCalculator.java:80` clips the in-progress trip start to the range `from` when no closed trip exists (fabricated start/duration); console e2e specs expect "Connected" but the UI says "Conectado"; make `ActivityReportEndpointTest` print or fix its time dependence.
