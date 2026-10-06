# QA follow-ups (after prod-qa-findings, PRs #78-#86)

## Objective
Close the follow-ups left by prod-qa-findings reviews and deferred items. Transloco excluded (user, 2026-09-25).

## Scope / constraints
- Branches stack on the unmerged chain tip (`fix/console-activity-in-progress-trip`, #86) because they touch files changed there. Delivery: auto commits + PRs, stacked-to-main (cached from prod-qa-findings).
- No behavior changes beyond each item. No AI attribution in commits.

## TDD
Off. Checks: `npx nx test console`, `npx nx lint console`, `npx nx build console --configuration=production`, backend `backend/gradlew.bat :<module>:check` (Docker running), `npx nx run api-client:check-drift` when DTOs change.

## Tasks
- [x] F1 Track window refreshes while the same vehicle stays selected (R3-003)
- [x] F2 Geofence editor: toggle only the managed controls instead of `form.enable()` on the whole group (R3-form-enable-all)
- [x] F3 Wildcard route spec navigates an unknown URL through the Router (R3-route-structural-test)
- [x] F4 `geofenceBounds` handles geofences crossing the antimeridian (R3-bounds-antimeridian)
- [x] F5 Service spec for activity report `toInProgressTrip` mapping
- [x] F6 Injectable `Clock` in `ActivityReportService` + UTC-midnight gate test
- [x] F7 Extract trip segmentation rules shared by processor and api (remove the hand-mirrored copy)
- [x] F8 Logout mixed-content redirect (investigate; fix if in repo config)
- [x] F9 Initial bundle 603 kB vs 600 kB warning budget

## Routes
- F1-F5: one delegated console writer (2+ non-trivial files).
- F6-F7: one delegated backend writer.
- F8-F9: delegated read-only investigation first.

## Progress / evidence

## Next step
Start F1-F5 writer and F8/F9 investigation.

### F8/F9 investigation (delegated Explore)
- F8 root cause: `backend/api/.../security/SecurityConfig.java` has no `.logout(...)` config -> Spring default `SimpleUrlLogoutSuccessHandler` 302s to `/login?logout` built from `getScheme()`; behind Caddy (`docker/caddy/Caddyfile`, sets X-Forwarded-Proto) Spring doesn't honor forwarded headers (no `forward-headers-strategy`) -> `http://` Location -> mixed content. Fix in-repo: logout success handler returning a status (no redirect), matching the file's formLogin pattern; plus `server.forward-headers-strategy: framework` as defense in depth. Route: delegated backend writer in isolated worktree, branch from origin/main (independent PR).
- F9 root cause: `apps/console/src/app/app.config.ts` imports full `@primeuix/themes/aura` barrel (~120 kB of ~100 component presets) while ~8 PrimeNG components are used. Fix: preset from `aura/base` + only used component tokens. Budget bump rejected (avoidable weight). Route: console writer after F1-F5 (same branch).
- F8 done: 551d6b1 on `fix/api-logout-no-redirect` (from origin/main, worktree `.claude/worktrees/agent-a76f72b931318c50a`), delegated writer: `.logout()` success handler 200 no redirect, `server.forward-headers-strategy: framework` in application-prod.yml, tests (logout 200 no Location + session ends; forwarded proto -> https server URL). `:api:check` ok. 98 lines, assess high (security) -> consent pending; PR pending.
- Note: #78 merged into main (e27b106) meanwhile.
- Delivery incident: #79-#82 and #84 were merged into their stacked base branches, not main (main has only #78 + #83). Opened #87 `fix/alert-ack-audit` -> main (no new code, merges cleanly) to bring T4-T7, T9 into main. After merge: retarget #85 to main.
- F1-F5 done on `fix/qa-followups-console` (delegated writer): c74c248 F1 track refresh tick (TRACK_REFRESH_INTERVAL_MS 60s; same-id reselect cannot notify, self-heals on tick), eb0427b F2 toggle name/rule + dwellSecs only when ON_DWELL (save uses getRawValue, verified), 30e0337 F3 Router navigation spec, 04526c4 F4 antimeridian, 10dbfbc F5 service spec. Parent: first `nx test console` run showed 8 failures under load (concurrent Gradle in worktree), not reproducible in 4 reruns; hardened F1 spec (poll up to 2 s instead of fixed 60 ms sleep) af8a208. console 277/277, lint ok. Assess: medium 346 lines under_budget.
- F8 review (4 lenses, lineage review-3a0eed6bc61c6d64) approved+acknowledged; fixes delegated in worktree: native forward-headers strategy, dedicated prod test class, comments, Set-Cookie assertion.
- F9 done 3a72a7c (delegated): Aura base + 8 used components; initial 603 -> 514.46 kB, no warning (parent re-ran build). PR #88 (F1-F5, F9, af8a208) base #86; slice 390 lines under_budget, unreviewed.
- F6-F7 started on `fix/shared-trip-rules` from `fix/api-activity-in-progress-trip` (#85).
- F8 review fixes 673625d (native strategy, ForwardedHeadersTest, cookie expiry asserted); PR #89 to main.
- F6-F7 done (delegated backend writer, route: delegated direct, writer trigger 2+ files): dddb7da Clock bean + injection in ActivityReportService (23 lines), a505e1a fixed-Clock UTC-midnight gate test (127 lines), 01f616b shared trip rules in geo-core (`PositionSample`, `MotionReplay`, `TripSegmentRules`; 988 lines; removed unreachable `tripStartIdx != null` guard, behavior-neutral). Checks: `:geo-core:check` green (parent re-ran); `:api:check` 23 and `:processor:check` 19 failures, all Testcontainers "no Docker daemon" (Docker Desktop down) -> integration coverage PENDING. Non-Docker unit tests green.

- F6-F7 checks with Docker: `:api:check :processor:check` BUILD SUCCESSFUL (parent, 10m). Review lineage review-8a41b7b1a1877971 (range 42f3c1d..01f616b, medium, slice_budget_reached, consent granted, 1 lens reliability): approved and acknowledged (authority burned). Advisory follow-up (non-blocking): R3-clock-gate-fixture-inconsistent, ActivityReportServiceClockGateTest.java:104-114 (fixture positions dated 2025 while report day is 2026). Reviewed boundary now 01f616b.

- F6-F7 delivered: PR #90 fix/shared-trip-rules -> main (#85 already merged, base commits in main).

- Neon simulator bound: df16949 on fix/simulator-bounded-run (from main), compose config parses; review review-a6e97a211e43a4e4 (high, process_boundary, 4 lenses, consent granted) approved+acknowledged; advisory warnings R3-restart-policy-exit-code-coupling, R3-run-duration-contract-unproved (verify clean exit 0 on server). PR #91 -> main.

## Next step
User merges #90 and #91. On server: confirm simulator exits 0 after run and stays stopped. Advisory: align fixture dates in ActivityReportServiceClockGateTest.
