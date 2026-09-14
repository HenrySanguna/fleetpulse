```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:eb3f21da5205f9f33db5bc5b66ba98bb6e411950c44b44f7b6ee07c50697b0f0
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 4/4
scenarios: 6/6
test_command: npx nx run-many -t test -p console console-ui --skip-nx-cache && (cd backend && ./gradlew :api:test)
test_exit_code: 0
test_output_hash: sha256:2f4daccf95cb61035904c3effbfa5b4edcc7b83f3e02a25c8270e7a86efbad55
build_command: npx nx run-many -t build -p console console-ui --skip-nx-cache && (cd backend && ./gradlew :api:build -x test)
build_exit_code: 0
build_output_hash: sha256:5ab1d5b1b9a911f490b8ac1adcf8ca78755265958243a6b414e0dddd3a740544
```

## Verification Report

**Change**: 04-add-live-map
**Version**: spec.md as of openspec/changes/04-add-live-map/specs/live-map/spec.md (4 requirements, 6 scenarios)
**Mode**: Standard (strict_tdd: false, per openspec/config.yaml)
**Verified against**: branch feat/live-map-wu6-e2e-and-dod @ 59ad3fb (tip of the 6-WU chain), diff vs main = 93 files, +6285/-1098

### Completeness
| Metric | Value |
|--------|-------|
| Tasks total | 29 |
| Tasks complete | 27 |
| Tasks incomplete | 2 (task 6.5, DoD item 1, both honestly marked open, both environment-blocked, see Issues) |

### Build and Tests Execution

**Build**: PASS
```text
npx nx run-many -t build -p console console-ui --skip-nx-cache
  console-ui: build succeeded (Angular library build)
  console: Application bundle generation complete. Initial total 560.11 kB (matches tasks.md's documented
    budget-raise note, 500kb to 600kb warning threshold). Output: dist/apps/console
(cd backend && ./gradlew :api:build -x test)
  BUILD SUCCESSFUL in 2s, 15 actionable tasks (5 executed, 10 up-to-date)
Exit: 0 / 0
```

**Tests**: 80 passed (frontend) plus all backend suites passed, 0 failed
```text
npx nx run-many -t test -p console console-ui --skip-nx-cache
  console-ui: Test Files 4 passed (4) / Tests 13 passed (13)
  console:    Test Files 10 passed (10) / Tests 67 passed (67)
    (jsdom canvas getContext warnings are expected stub noise from vehicle-symbol.util.spec.ts's
     SDF-icon tests, not failures)
(cd backend && ./gradlew :api:test)
  BUILD SUCCESSFUL in 3m 8s, 14 actionable tasks (4 executed, 10 up-to-date)
  All JUnit XML reports show 0 failures, 0 errors across every test-results file (26 files), including
  TEST-dev.fleetpulse.api.fleet.FleetStateEndpointTest.xml and
  TEST-dev.fleetpulse.api.fleet.VehicleTrackEndpointTest.xml (WU2's new endpoint coverage).
  Late-shutdown HikariPool/spring-session stack traces after BUILD SUCCESSFUL are Testcontainers
  teardown noise (a scheduled session-cleanup task firing after the container pool already closed),
  not test failures, confirmed by cross-checking BUILD SUCCESSFUL plus 0 XML failures.
Exit: 0 / 0
```

**Lint**: npx nx run-many -t lint -p console console-ui console-e2e -> all 3 projects pass, 0 problems.

**Coverage**: not gated (coverage_threshold: 0 in openspec/config.yaml) -> not applicable.

**E2E (Playwright, apps/console-e2e)**: run separately from the strict single test_command above (three
browser projects, real docker-compose Mosquitto already running healthy).

Attempt 1, committed config (playwright.config.ts, default projects, `npx nx e2e console-e2e`):
9 test runs attempted (chromium/firefox/webkit times 3 specs). Firefox and webkit browser binaries are
not installed on this machine (browserType.launch: Executable doesn't exist), an environment gap unrelated
to this change. The 3 chromium runs all failed identically: `page.goto('/')` aborts with
`net::ERR_ABORTED; maybe frame was detached?` and a 30000ms test timeout, for every one of the 3 specs,
before any application code executes at all. This reproduces the same class of finding tasks.md documents
for the WU6 session (an unsigned Playwright Chromium binary unable to complete network requests in this
sandbox), though it manifests here as a fast ERR_ABORTED plus timeout rather than an indefinite hang.

Attempt 2, session-only channel msedge override (temporary scratch Playwright config, created and deleted
within this verify session, never committed, the same session-only workaround WU6's own apply session used):

live-map-http-independence.spec.ts: PASS (5.6s). Real MQTT publish, real UI update, connection badge
stays Connected after breakApiHttp() aborts every /api/** call.

live-map-connection-resync.spec.ts: PASS (9.8s). Real forced double-reconnect, disconnect notice shown,
full resync cycle observed.

live-map-mqtt-sync.spec.ts (test 6.5): FAIL (14.5s), but not at the final marker-moved assertion tasks.md's
narrative focuses on. It failed at the PRE-publish sanity check (hasFeatureNear polling for the vehicle's
initial reported position on the rendered map), a 10000ms timeout, before the test ever reaches the MQTT
publish step. See Issues, WARNING 3 below for what this means for the verified passing claim's precision.

A follow-up one-off diagnostic spec (written and deleted within this session) confirmed the root cause
directly: window.__fleetpulseLiveMap (set only inside MapLibre's map.on load handler) never becomes
defined within 12 seconds of a Connected MQTT status, with zero browser console errors logged, meaning
MapLibre's own load event never fires in this sandboxed VM, independent of network/MQTT connectivity
(proven working by the other two passing specs). This independently corroborates WU6's own diagnosis
(styledata fires, tiles and sprite return 200, a standalone WebGL context creates fine, but
isStyleLoaded()/load/idle never resolve) from a separate session using a different diagnostic method,
which is strong evidence this is a genuine sandboxed-VM or WebGL rendering limitation, not a fabricated
excuse or a hidden code defect.

### Spec Compliance Matrix
| Requirement | Scenario | Test | Result |
|---|---|---|---|
| Sincronizacion inicial sin perdida de actualizaciones | Actualizacion durante la carga del snapshot | fleet-startup.service.spec.ts: applies messages buffered while the snapshot request is in flight, right after the snapshot | COMPLIANT |
| Sincronizacion inicial sin perdida de actualizaciones | Mensaje anterior al snapshot descartado | fleet.store.spec.ts: applyUpdate discards a telemetry update older than the currently known position; fleet-startup.service.spec.ts: discards a buffered message older than the snapshot for that vehicle | COMPLIANT |
| Resincronizacion tras reconexion | Reconexion tras perdida de red | apps/console-e2e/src/live-map-connection-resync.spec.ts (E2E, real broker plus forced double-reconnect) | COMPLIANT, re-run and confirmed passing this session |
| Separacion entre posicion interpolada y posicion reportada | Consulta de detalle durante una interpolacion | vehicle-interpolation.spec.ts: never substitutes the interpolated position into FleetStore; vehicle-detail.component.spec.ts; live-map-page.component.spec.ts: shows the exact reported state for the selected vehicle regardless of visibleVehicles filtering | COMPLIANT |
| Separacion entre posicion interpolada y posicion reportada | Ausencia prolongada de actualizaciones | vehicle-interpolation.spec.ts: freezes at the last reported position and marks stale once the window is exceeded | COMPLIANT |
| Independencia del mapa en vivo respecto al servicio HTTP | Servicio HTTP no disponible | apps/console-e2e/src/live-map-http-independence.spec.ts (E2E, real breakApiHttp plus real MQTT publish) | COMPLIANT, re-run and confirmed passing this session |

**Compliance summary**: 6/6 scenarios compliant.

### Correctness (Static Evidence, spot-checked directly against source)
| Task cluster | Status | Notes |
|---|---|---|
| 1.1-1.4 MQTT client | Implemented | mqtt-connection.service.ts: exponential backoff (min(1000 times 2^n, 30000)), credential renewal (expiresAt minus 30s), subscribe-on-connect to fleet/{org}/vehicle/+/{telemetry,status}. Matches tasks.md claims exactly. |
| 2.1 Backend endpoints | Implemented | FleetStateController/VehicleTrackController, org-scoped via CurrentDispatcher, 404-not-403 ownership check confirmed in VehicleTrackEndpointTest.hidesAVehicleFromAnotherOrganizationAsNotFound. |
| 2.2-2.4 Startup sequence | Implemented | FleetStartupService: subscribe-before-snapshot via connectedStatus$ plus a buffer array plus switchMap; matches design.md's 7-step sequence precisely. |
| 2.3/3.1 Monotonicity guard | Implemented | FleetStore.isStale() compares Date.parse() instants, lives in one place (applyUpdate), applies to both buffer-replay and live paths. |
| 3.3/4.6 Track resource | Implemented | VehicleTrackService, httpResource, independent of FleetStore (confirmed: renderTrack failure path never touches the vehicle layer). |
| 4.1-4.5 Map rendering | Implemented | OpenFreeMap style URL, canvas-drawn SDF icon (enables icon-color recoloring), icon-rotate from reported heading, VehicleInterpolationEngine.sampleOne's clamp to [0,1] is exactly the stop-never-extrapolate rule. |
| 5.1-5.4 Console UI | Implemented | libs/console-ui hand-scaffolded (see Coherence table), 4 presentational components confirmed input/output-only, no service injection. |
| 6.1-6.4 Unit tests | Implemented and passing | Confirmed via fresh (skip-nx-cache) run: 67 plus 13 equals 80 of 80 passing. |
| 6.5 E2E marker-move | Partial, unchecked | See Issues WARNING 1 and 3. |
| 6.6 E2E resync | Implemented and passing | Confirmed passing this session (msedge). |
| DoD 1, 50-vehicle soak | Structural only, unchecked | See Issues WARNING 2. |
| DoD 2, api-down independence | Implemented and passing | Confirmed passing this session (msedge). |

### Coherence (Design)
| Decision | Followed | Notes |
|---|---|---|
| Subscribe before snapshot, buffer, replay, monotonicity guard | Yes | FleetStartupService/FleetStore match design.md's 7-step sequence and guard rule exactly. |
| SignalStore for the stream, httpResource for HTTP | Yes | FleetStore (stream) vs VehicleTrackService (httpResource) cleanly separated per design.md's stated rationale. |
| Interpolation purely visual, never substituted into real state | Yes | LiveMapComponent never writes interpolated samples back to FleetStore, proven by both a unit test and, for 2 of 3 E2E specs, real browser assertions. |
| organizationId source via GET /api/dispatchers/me | Yes, documented deviation | design.md's sequence starts at obtaining MQTT credentials without addressing this; resolved cleanly, non-breaking, reused an already-existing change-02 endpoint. |
| motionState staleness bound | Yes, documented deviation | The wire telemetry payload has no motionState field; the store keeps the last-known value, bounded by the existing reconnect-resync cycle. Does not break any spec scenario. |
| OpenFreeMap tile provider | Yes, documented deviation | design.md names MapLibre GL but not a tile source; OpenFreeMap satisfies project.md's literal no-paid-token constraint more strictly than MapTiler or Stadia free tiers. |
| Canvas-drawn SDF icon | Yes, documented deviation | No icon asset existed; SDF is required specifically to enable per-feature icon-color (task 4.3). Reasonable, non-breaking. |
| Hand-scaffolded libs/console-ui | Yes, documented deviation | The Nx Angular library generator's default vitest-analog unitTestRunner triggers a real ERESOLVE against this repo's pinned Angular compiler-cli version; reusing apps/console's own build-based unit-test executor is a documented, supported pattern, not a fragile workaround. |
| libs/api-client responseType blob bugfix | Yes, necessary fix, not a deviation | Root cause (Configuration.selectHeaderAccept falling back to a wildcard Accept header that fails the generator's own isJsonMime check) verified by reading libs/api-client/src/configuration.ts directly, and the fix (getJson helper, apps/console/src/app/core/http/api-client-json-get.ts) confirmed applied at exactly the 3 claimed call sites; VehicleTrackService's httpResource correctly left untouched. |

### Issues Found

**CRITICAL**: None. Every spec.md requirement/scenario (4 requirements, 6 scenarios) has a passing, runtime-executed covering test, confirmed by re-running the suites in this session: frontend fresh skip-nx-cache run, 80 of 80 pass; backend full api:test, 0 failures/errors across 26 XML reports; E2E, 2 of 3 specs re-run and confirmed passing this session, covering the 4 non-6.5/DoD1 spec.md scenarios.

**WARNING**:

1. Task 6.5 (E2E: a published MQTT message moves the rendered marker) is genuinely incomplete in tasks.md,
blocked by a reproducible, environment-specific MapLibre GL rendering stall (the map's load event never
fires; window.__fleetpulseLiveMap never populates). This was independently reproduced in a separate
diagnostic run this session, a different agent and session than WU6's own, which is strong corroboration
this is a genuine sandboxed-VM or WebGL limitation and not a one-off fluke or a hidden application defect.
Not CRITICAL: the spec.md scenario this test partially targets, Separacion entre posicion interpolada y
posicion reportada, already has independent, passing runtime coverage at the unit and container level (see
Compliance Matrix), so no spec requirement is left unproven. The task itself, however, is honestly still
open and should stay open in tasks.md rather than be marked done.

2. DoD item 1 (50-vehicle, 10-minute memory soak) is genuinely incomplete, blocked by the same rendering
stall (a soak needs the map to actually render). The structural reasoning documented in tasks.md is sound
and each of its 5 claims traces to an already-passing, runtime-executed unit test, no fabricated pass.
This matches this project's own 03-add-telemetry-ingest WU9 precedent for a WARNING (shortened and
structurally reasoned, not silently skipped), not the no-test-at-all CRITICAL precedent from that same
change's first verify pass.

3. Documentation-precision gap in tasks.md's test-6.5 narrative: it states the committed test's own
assertions up to reported position updates in the UI after publish passed. This session's independent
reproduction of the actual committed spec (live-map-mqtt-sync.spec.ts, msedge channel, real network
confirmed working via the 2 other passing E2E specs) shows it fails earlier than that, at the pre-publish
sanity check, before the test ever reaches the MQTT publish step. The publish-to-UI data-flow claim
credited partly to the committed test's own assertions therefore appears to have only ever been
demonstrated by a separate, deleted, throwaway verification script, in both this session and, per its own
description, WU6's, never by the checked-in spec file reaching that point in either session. This does not
change the underlying functional conclusion, since that same data flow is independently proven elsewhere
(test 6.6, DoD 2, and the unit tests all exercise the identical MQTT-publish-updates-FleetStore-updates-UI
path), but the specific up-to-that-point framing for the committed test overstates what the checked-in
file itself has been observed to do. Recommend tightening that sentence in tasks.md before or at archive.

**SUGGESTION**:

1. Re-attempt task 6.5 and DoD 1 on a real CI runner, such as this repo's own ubuntu-latest GitHub Actions
workflow, which the launch materials note has no Edge browser and no known firewall restriction, to close
both items for real rather than leaving them permanently unchecked.

2. FleetStartupService.runStartupCycle's snapshot-fetch failure is logged but not retried on its own, a
documented and deliberate tradeoff to avoid killing the outer reconnect stream. Worth a short design.md
addendum noting this explicitly as accepted risk, since it means a transient snapshot-fetch failure
between reconnects can leave the UI without fresh state until the next actual MQTT disconnect/reconnect.

### Verdict
PASS WITH WARNINGS
All 6 spec.md scenarios have passing runtime-verified coverage; the only 2 open tasks.md items (6.5, DoD 1)
are honestly documented, environment-blocked and independently reproduced this session, and non-spec-
blocking, consistent with archiving while carrying the 2 items forward as documented, re-attemptable
follow-ups.
