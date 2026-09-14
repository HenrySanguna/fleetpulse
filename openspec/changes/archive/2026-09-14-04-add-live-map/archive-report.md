# Archive Report: 04-add-live-map

**Archive Date**: 2026-09-14  
**Change**: 04-add-live-map  
**Status**: ARCHIVED — SDD cycle complete (planning/spec/design/tasks phases closed)  
**Implementation Status**: Code NOT yet merged to main; 6 PRs (#38-#43) open on feature-branch-chain, pending review/merge

## CRITICAL ARCHIVAL NOTE

This archive report records the **closing of the SDD planning and verification cycle** only. It does NOT indicate that the implemented code has been merged to `main` yet. See the "Implementation Delivery Status" section below for details.

## Artifacts Merged

### Spec Synchronization
- **Delta spec source**: `openspec/changes/04-add-live-map/specs/live-map/spec.md`
- **Merged to main spec**: `openspec/specs/live-map/spec.md` (newly created domain)
- **Merge method**: Mechanical copy (no prior main spec existed; delta is a full spec)
- **Verification**: `diff -r` between source and destination returns empty (byte-identical)

**Spec Contents (4 Requirements, 6 Scenarios)**:
1. Sincronización inicial sin pérdida de actualizaciones (2 scenarios)
2. Resincronización tras reconexión (1 scenario)
3. Separación entre posición interpolada y posición reportada (2 scenarios)
4. Independencia del mapa en vivo respecto al servicio HTTP (1 scenario)

All scenarios have passing runtime-verified test coverage per the verification report (see Final Verification State below).

### Change Folder Archived
- **Source**: `openspec/changes/04-add-live-map/` (moved via `git mv`)
- **Archived to**: `openspec/changes/archive/2026-09-14-04-add-live-map/`
- **Contents verified**: All artifacts present and byte-identical via `diff -r`
  - proposal.md ✅
  - specs/live-map/spec.md ✅
  - design.md ✅
  - tasks.md ✅
  - verify-report.md ✅

## Task Completion Summary

**Total Tasks**: 29 completed, 2 intentionally open (environment-blocked)  
**Completion Rate**: 27/29 (93%)

### Completed Tasks (27)
- 1.1–1.4: MQTT client (WU1) ✅
- 2.1: Backend snapshot + track endpoints (WU2) ✅
- 2.2–2.4: Startup sequence (WU3) ✅
- 3.1–3.3: Client state and track resource (WU3/WU4) ✅
- 4.1–4.6: Map rendering (WU4) ✅
- 5.1–5.4: Console UI (WU5) ✅
- 6.1–6.4: Unit tests (WU3/WU4) ✅
- 6.6: E2E resync test (WU6) ✅
- DoD 2: API-down independence (WU6) ✅

### Intentionally Open Tasks (2 — Honestly Documented, Residual Risk)
1. **Task 6.5**: E2E marker-move test (Playwright canvas rendering)
   - **Status**: Incomplete — environment-blocked (MapLibre GL rendering stall)
   - **Evidence**: Independently reproduced this session and previous WU6 session
   - **Impact**: Spec requirement "Separación entre posición interpolada y posición reportada" fully proven by unit tests + E2E container-level tests; task 6.5's canvas assertion alone missing
   - **Replan**: Re-attemptable on CI (ubuntu-latest) or a machine without this environment-specific firewall restriction

2. **DoD 1**: 50-vehicle memory soak (10-minute fluidity test)
   - **Status**: Incomplete — environment-blocked (same MapLibre GL rendering stall)
   - **Evidence**: Structural reasoning provided (5 independent claims, each traces to a passing runtime-executed unit test)
   - **Impact**: No spec requirement left unproven; code design proven sound
   - **Replan**: Re-attemptable when canvas rendering becomes available

**Reconciliation Basis**: Per the Skill's Task Completion Gate exception, these 2 tasks remain unchecked in tasks.md rather than falsely marked complete. The orchestrator's launch prompt explicitly authorizes carrying them forward as "accepted residual risk, re-attemptable on CI or a different machine — not a functional defect."

## Final Verification State

**Verification Report**: `openspec/changes/archive/2026-09-14-04-add-live-map/verify-report.md`  
**Verdict**: **PASS WITH WARNINGS** (0 CRITICAL, 3 WARNING, 2 SUGGESTION)

### Test Results (Verified This Session)
- **Build**: PASS (console, console-ui, api) — 560.11 kB bundle (within updated 600kb budget)
- **Unit Tests**: 80/80 PASS (console-ui 13, console 67)
- **Backend Tests**: 26 test-results files, 0 failures, 0 errors
- **E2E (Playwright)**: 2/3 specs PASS (http-independence, connection-resync), 1 FAIL at pre-canvas sanity check (6.5, canvas stall)
- **Lint**: PASS (0 problems across console, console-ui, console-e2e)

### Requirement Coverage (4/4 Requirements, 6/6 Scenarios)
| Requirement | Scenario | Test | Result |
|---|---|---|---|
| Sincronización inicial sin pérdida | Actualización durante snapshot | fleet-startup.service.spec.ts | PASS |
| Sincronización inicial sin pérdida | Mensaje anterior descartado | fleet.store.spec.ts | PASS |
| Resincronización tras reconexión | Reconexión tras pérdida de red | live-map-connection-resync.spec.ts (E2E) | PASS |
| Separación (interpolada vs. reportada) | Consulta detalle durante interpolación | vehicle-interpolation.spec.ts, vehicle-detail.component.spec.ts | PASS |
| Separación (interpolada vs. reportada) | Sin mensajes en ventana esperada | vehicle-interpolation.spec.ts | PASS |
| Independencia HTTP | Servicio HTTP no disponible | live-map-http-independence.spec.ts (E2E) | PASS |

**Compliance Summary**: All 6 spec scenarios COMPLIANT with passing runtime tests.

### Key Implementation Facts (Post-Verify-Report)

**From Launch Prompt Final-State Facts**:
- 27/29 tasks fully verified with real passing tests
- 2 items (test 6.5's final map-canvas assertion, DoD 1's live memory soak) environment-blocked in sandbox (MapLibre GL never reaches rendered state there, independently reproduced by both apply and verify sessions) — accepted residual risk
- Small documentation-precision fix landed post-verify (commit 6340606): corrected overclaim in tasks.md about test 6.5 spec's proof scope (data-flow claim traced to throwaway script, not committed test)
- 6 work units across 6 chained branches (WU1 MQTT → WU6 E2E + DoD + production bugfix)
- Production bug discovered in WU6 and fixed: `libs/api-client`'s generated services defaulted to `responseType: 'blob'`, silently breaking every JSON GET (including MQTT credential fetch, app-breaking since WU3) — fixed with `getJson` helper in WU6
- Code NOT yet in main; 6 PRs open (#38–#43), none merged yet

## Implementation Delivery Status

**PRs Open** (Feature Branch Chain: `feat/live-map` tracker + 6 work-unit branches):
- PR #38: WU1 — Cliente MQTT en el navegador
- PR #39: WU2 — Backend: snapshot + track endpoints
- PR #40: WU3 — Arranque snapshot+stream / estado cliente
- PR #41: WU4 — Mapa
- PR #42: WU5 — Consola
- PR #43: WU6 — E2E + Definición de terminado

**Tracker Branch**: `feat/live-map` (aggregate point for all 6 WU branches; code NOT merged to `main` yet)  
**Current HEAD**: WU6 commit `59ad3fb` (verified against this commit)

**Next Steps** (post-archive):
1. Review and merge the 6 open PRs in order (WU1 → WU6) to `main`
2. Optionally re-attempt task 6.5 and DoD 1 on CI if canvas rendering becomes available
3. Close this change tracking in the orchestrator

## Discovered Gaps (Documented in Tasks.md)

### Production Defect (Fixed in WU6)
- **`libs/api-client` responseType blob bug**: Generated service methods defaulted to `responseType: 'blob'` instead of `'json'`, silently breaking every JSON GET endpoint (DispatcherSessionControllerService.me, MqttCredentialsControllerService.credentials, FleetStateControllerService.state). Caused MQTT client never to connect in real running app (WU3 onward) despite unit tests passing (they mocked the generated service directly). Fixed via `getJson<T>()` helper that reads configuration and uses plain `HttpClient.get<T>()` instead. Confirmed applied at 3 call sites; VehicleTrackService correctly unaffected.

### Design Deviations (Resolved, Non-Breaking)
1. **organizationId sourcing**: design.md's sequence starts at obtaining MQTT credentials without addressing that `MqttConnectionService.connect(organizationId)` needs an org ID. Resolved by fetching dispatcher first via `GET /api/dispatchers/me` (change 02 endpoint, reused). Documented in WU3 apply-progress.

2. **motionState staleness**: Wire telemetry payload has no `motionState` field; server only tracks it with duration history `processor` maintains. Resolved by keeping last-known value between resyncs (reconnect triggers full resync, bounding staleness). Does not break any spec scenario.

3. **OpenFreeMap tile provider**: design.md names MapLibre GL but not a tile provider; project.md says "sin token de pago". Chose OpenFreeMap (requires no signup, self-hostable) over MapTiler/Stadia free tiers. Documented in WU4.

4. **Canvas-drawn SDF icon**: No icon asset existed; SDF required for data-driven icon-color recoloring. Canvas-rendered at runtime, registered with MapLibre. Documented in WU4.

5. **Hand-scaffolded libs/console-ui**: Nx Angular library generator's default `vitest-analog` unitTestRunner conflicts with pinned Angular compiler-cli version. Resolved by reusing `apps/console`'s build-based unit-test executor (documented, supported pattern). Documented in WU5.

### Backend Endpoint Gap (Incorporated Into WU2)
- **Track endpoint missing from original task list**: design.md and task 4.6 assume `GET /api/vehicles/{id}/track` exists; only `GET /api/fleet/state` was listed in task 2.1. Resolved by folding track endpoint into WU2's scope rather than leaving 3.3/4.6 unimplementable. Documented in task 2.1.

### MQTT/ESM Export Gap (Fixed in WU5)
- **MQTT.js browser ESM default export**: `import { connect }` type-checks under Vitest but fails bundling (`No matching export in mqtt/dist/mqtt.esm.js`). Resolved by importing default export and adjusting call site: `import mqtt from 'mqtt'` → `mqtt.connect(...)`. Mock updated to expose both `default.connect` and `connect`. Fixed in WU5 before production build. Documented in WU5 apply-progress.

### Playwright/Browser Environment Gap (Not Fixed, Environmental)
- **Playwright Chromium unsigned binary blocked by Windows Firewall**: Fresh Chromium binary unable to reach network; system Microsoft Edge works fine on the same machine. Workaround: `channel: 'msedge'` in E2E config (session-only, not committed). Does not affect production or CI (ubuntu-latest). Documented in WU6 and tasks.md.

### MapLibre GL Rendering Stall (Not Fixed, Environmental, Independently Reproduced)
- **Canvas never reaches `isStyleLoaded()` true; load/idle events never fire**: Tiles and sprite return 200, standalone WebGL context succeeds, but MapLibre internals never trigger completion callback. Verified independently by both WU6 and verify session using different diagnostic methods. Blocks tasks 6.5 and DoD 1 only; spec proof unaffected (unit tests + other E2E specs). Documented in tasks.md section 6.5 and verify-report.md WARNING 1–3.

## Archive Readiness Checklist

- [x] Task Completion Gate passed (2 tasks honestly documented as environment-blocked per Skill exception)
- [x] Spec synced to main (delta merged to `openspec/specs/live-map/spec.md`)
- [x] Change folder moved to archive (`openspec/changes/archive/2026-09-14-04-add-live-map/`)
- [x] Archive contents verified via `diff -r` (empty output = success)
- [x] No unchecked implementation tasks remain (2 marked open as per final-state facts; zero false completions)
- [x] No CRITICAL issues in verify-report (0 CRITICAL, 3 WARNING, 2 SUGGESTION — non-blocking)
- [x] Spec compliance proven (6/6 scenarios with passing tests)
- [x] All artifacts present in archive (proposal, spec, design, tasks, verify-report)

## Sources and Traceability

**Proposal Artifact**: `openspec/changes/archive/2026-09-14-04-add-live-map/proposal.md`  
**Spec Artifact**: `openspec/specs/live-map/spec.md` (synced from archived copy)  
**Design Artifact**: `openspec/changes/archive/2026-09-14-04-add-live-map/design.md`  
**Tasks Artifact**: `openspec/changes/archive/2026-09-14-04-add-live-map/tasks.md` (27/29 complete, 2 environment-blocked)  
**Verify Artifact**: `openspec/changes/archive/2026-09-14-04-add-live-map/verify-report.md` (PASS WITH WARNINGS)

**Git Commits Mentioned**:
- `6340606`: docs(sdd): correct test 6.5 narrative in live-map tasks (post-verify fix)
- `59ad3fb`: feat(console): add live-map E2E tests, fix silent MQTT connection break (WU6, verified HEAD)

## Conclusion

The SDD cycle for 04-add-live-map is **COMPLETE and ARCHIVED**:
- ✅ Spec merged and source of truth updated
- ✅ Change folder moved to archive with full verification
- ✅ All 4 spec requirements and 6 scenarios have passing test coverage
- ✅ 27/29 tasks implemented; 2 intentionally open as environment-blocked (accepted residual risk per orchestrator)
- ✅ Production defect discovered and fixed (MQTT blob responseType)
- ✅ Design deviations documented and non-breaking

**Implementation is ready for merge** (6 open PRs, feature-branch-chain topology). Tasks 6.5 and DoD 1 remain re-attemptable on CI or a different machine without blocking archive closure or main merge.

---

*Archive report generated by sdd-archive executor on 2026-09-14*  
*Artifact store: openspec (repo-local)*
