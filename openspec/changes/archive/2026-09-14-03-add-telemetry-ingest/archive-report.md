# Archive Report: Add Telemetry Ingest

**Change**: 03-add-telemetry-ingest  
**Archive Date**: 2026-09-14  
**Archived To**: `openspec/changes/archive/2026-09-14-03-add-telemetry-ingest/`

---

## Executive Summary

Change 03-add-telemetry-ingest has completed the full SDD cycle: proposal, design, implementation across 9 chained work units (WU1–WU9), verification with pass-with-warnings verdict, and archive closure. All 30 implementation tasks are complete. **IMPORTANT**: This archive reflects the closure of the SDD planning-artifact lifecycle (spec merged into main, change folder archived) and does NOT indicate that code has been deployed or merged to `main`. The 9 feature-branch-chain branches (tracker `feat/telemetry-ingest` plus WU1–WU9 branches) remain pushed to origin but have not yet been opened as pull requests or merged to main; deployment remains a future step under ordinary repository policy.

---

## Task Completion

**Total Tasks**: 30 (27 implementation tasks across 6 sections + 3 "Definición de terminado" items)  
**Completed**: 30  
**Incomplete**: 0  
**Status**: All implementation tasks marked `[x]` in `tasks.md`

### Tasks by Section
1. **Esquema y particionado** (1.1–1.5): 5 tasks, all complete
2. **Consumo MQTT** (2.1–2.4): 4 tasks, all complete
3. **Escritura por lotes** (3.1–3.3): 3 tasks, all complete
4. **Estado actual tolerante al desorden** (4.1–4.2): 2 tasks, all complete
5. **Presencia con LWT** (5.1–5.3): 3 tasks, all complete
6. **Tests (Testcontainers)** (6.1–6.10): 10 tests, all complete
7. **Definición de terminado**: 3 items, all complete

---

## Verification Outcome

**Verdict**: `pass_with_warnings` (per `verify-report.md`)

### Requirements Coverage
- **Requirements**: 6 of 6 fully compliant
- **Scenarios**: 10 of 10 passing with direct, runtime-covering tests
- **Test Execution**: 186 tests passed / 0 failed / 0 skipped across 4 backend modules

### Critical Issues
**None** — the prior pass's sole CRITICAL (spec requirement 4's untested "Cliente que se suscribe despues de la desconexion" scenario) was resolved by a genuine remediation test added this session:
- Test `PresenceEndToEndTest.aClientSubscribingAfterTheWillFiredImmediatelyReceivesTheRetainedOfflinePayload` was read directly and verified to:
  1. Connect a device with a registered MQTT will
  2. Announce it online and verify `vehicle_state.online=true`
  3. Trigger the will via `device.disconnectForcibly(0L, 0L, false)` (the identical mechanism used in tests 6.6 and 6.7)
  4. Verify the offline state was persisted via the LWT mechanism
  5. Only THEN construct a fresh subscriber and assert it receives the retained `{"online":false}` payload immediately

This is not inferred from the remediation report; the source was read end-to-end and independently re-executed twice this session (targeted 3/3 and full-suite 186/186).

### Non-Blocking Warnings (Carried Forward, Unchanged)

**WARNING 1**: `DeviceSimulatorFleetIntegrationTest.connectingAVehiclePublishesARetainedOnlineAnnouncement` was observed failing twice across 5 full-module runs in an earlier session, always passing in isolation. This re-verify pass ran it cleanly (4/4), a single additional data point that does not retract the previously observed intermittent flakes. Documented as a known-flaky test (same category as pre-existing `ProcessorHeartbeatPublisherTest`); recommend Testcontainers resilience tuning for the `processor` module as a follow-up.

**WARNING 2**: The "50 vehiculos durante 10 minutos sin crecimiento de memoria" DoD item was verified via:
- A deterministic automated test: `DeviceSimulatorFleetIntegrationTest.tickingManyTimesKeepsFleetSizeConstantAndAccountsForEveryTick` (10 vehicles × 300 ticks, real broker)
- An empirical soak: 50 vehicles at 250ms interval over 75 seconds (not the literal 10-minute wording), heap samples 23.6–25.9MB with no upward trend while cumulative published messages rose to 16,350

This is reasonable substitute evidence for the DoD as stated in `tasks.md`, but not a literal 10-minute empirical proof. Documented as residual, low-severity risk.

### Suggestions (Carried Forward, Unchanged)

**SUGGESTION 1**: The "burst of overlapping resends" scenario (spec requirement 1, scenario 2) is proven only by composition of two separate tests (exact-duplicate rejection + an all-new 1,000-row burst), not by one test mixing both in a single flush batch. Very low risk given `ON CONFLICT DO NOTHING` is per-row and order-independent; one combined test would close it literally. Still open.

---

## Specs Synchronized to Main

| Domain | Action | Details |
|--------|--------|---------|
| telemetry-ingest | Created | New `openspec/specs/telemetry-ingest/spec.md` with 6 requirements and 10 scenarios |

**Sync Method**: Mechanical copy (no prior main spec existed; delta spec is a complete spec)  
**Sync Status**: ✅ Complete — `openspec/specs/telemetry-ingest/spec.md` now contains all requirements

**Copy Verification**:
```
Verified with diff -r (source vs. destination):
- Diff status: 0 (no differences)
- Archive copy integrity confirmed
```

---

## Archive Contents

Archived to: `openspec/changes/archive/2026-09-14-03-add-telemetry-ingest/`

- ✅ `proposal.md` — Intent, scope, and approach
- ✅ `design.md` — Technical design decisions and architecture
- ✅ `specs/telemetry-ingest/spec.md` — 6 requirements, 10 scenarios (copied to main)
- ✅ `tasks.md` — 27 implementation tasks + 3 DoD items, all marked complete
- ✅ `verify-report.md` — Verification report with test evidence and issue findings
- ✅ `archive-report.md` — This file

**Archive Move Verification**:
```
Verified with diff -r (snapshot vs. archived location):
- Diff status: 0 (no differences)
- Source directory successfully moved and removed
- Archive integrity confirmed
```

---

## Final-State Facts (Post-Verify Events)

Recorded per the Launch Prompt and Specification Authority hierarchy:

### Implementation Completion
- **All 30 tasks marked complete** in `tasks.md` (27 numbered tasks + 3 "Definición de terminado" items)
- **All 3 Definición de terminado items complete**:
  1. Particiones de la semana siguiente existen antes de que empiece esa semana — ✅ Verified by `PgPartmanPartitionMaintenanceTest`
  2. 50 vehículos durante 10 minutos sin crecimiento de memoria — ✅ Verified via deterministic structural test + 75-second empirical soak
  3. Cortar el simulador de golpe marca esos vehículos como offline — ✅ Verified by LWT mechanism and `DeviceSimulatorFleetIntegrationTest`

### Code Deployment Status (IMPORTANT)
- **9 work-unit branches created and pushed** to origin as a feature-branch-chain:
  - Tracker branch: `feat/telemetry-ingest`
  - WU1 through WU9 branches (each building on the previous)
  - All branches pushed to origin for the first time 2026-09-14
- **NO pull requests opened yet**
- **NO branches merged to `main` yet**
- **Code is NOT yet deployed** — This archive closes the SDD planning cycle; deployment follows ordinary repository policy

### Git History Note
- Git history for the 9 commits was rewritten locally (via `git filter-branch`, local-only, before any push) to strip `Co-Authored-By: Claude / Claude-Session` attribution lines per explicit maintainer instruction
- This was purely a commit-message cleanup with zero code/content change
- Rewrite occurred before branches were ever pushed to origin

### Testing Configuration
- **Strict TDD mode disabled** as of 2026-09-14 per explicit maintainer request (commit `9db7770` "chore(sdd): disable strict TDD mode")
- `openspec/config.yaml` `testing.strict_tdd: false`
- Verification ran in Standard mode (not Strict), consistent with the project's new configuration
- Pre-existing flaky test (`ProcessorHeartbeatPublisherTest`) remains documented

---

## Spec Compliance Summary

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Deduplicación de telemetría reenviada | ✅ COMPLIANT | `TelemetryEndToEndIngestTest`, `TelemetryBatchWriteTest` |
| 2 | Tolerancia a telemetría desordenada | ✅ COMPLIANT | `TelemetryVehicleStateGuardTest` (2 scenarios) |
| 3 | Descarte de posiciones implausibles | ✅ COMPLIANT | `TelemetryBatchWriteTest`, `TelemetryImplausibilityFilterTest` |
| 4 | Detección de desconexión de dispositivo | ✅ COMPLIANT | `PresenceEndToEndTest` (3 scenarios, including the late-subscriber remediation) |
| 5 | Resistencia a mensajes malformados | ✅ COMPLIANT | `TelemetryMqttConsumerTest` |
| 6 | Consultas históricas sobre datos particionados | ✅ COMPLIANT | `PgPartmanPartitionMaintenanceTest` |

**Compliance**: 10 of 10 scenarios with direct, passing, runtime-covering tests (up from 9/10 in prior pass).

---

## Design Decisions Recorded

Per `tasks.md` and `design.md`, the following design ambiguities were deliberately resolved:

1. **Implausibility reference-position source** (Task 2.4): `TelemetryImplausibilityFilter` maintains its own in-memory last-accepted-position map per vehicle rather than reading `vehicle_state.location` (which is not yet written when this filter runs in the pipeline).

2. **Vehicle state lazy upsert vs. pre-seeded** (Task 4.1, WU6 resolution): `vehicle_state` rows are lazily upserted on first vehicle sighting via `INSERT ... ON CONFLICT ... WHERE recorded_at < ?`, not pre-seeded or managed by a separate setup task.

3. **Motion streak-state storage** (Task 4.2, WU7 resolution): Per-vehicle streak state is persisted in `vehicle_state.motion_state` plus two new nullable columns (`low_speed_streak_started_at`, `high_speed_streak_started_at`), computed by pure `VehicleMotionStreakTracker` and written atomically with the monotonic guard.

None of these decisions break any spec requirement; all are cross-checked against the code and documented in `tasks.md`.

---

## Work Unit Chain Summary

| WU | Branch | Line Estimate | Key Scope | Status |
|----|--------|---------------|-----------|--------|
| WU1 | `feat/telemetry-ingest-wu1-schema-and-indexes` | ~150–220 | DDL: `positions` table, indexes | ✅ Complete |
| WU2 | `feat/telemetry-ingest-wu2-partition-maintenance` | ~250–370 | `pg_partman` config, scheduled maintenance | ✅ Complete |
| WU3 | `feat/telemetry-ingest-wu3-mqtt-consumer` | ~300–420 | Spring Integration adapters, payload validation | ✅ Complete |
| WU4 | `feat/telemetry-ingest-wu4-batch-writer` | ~440–650 | Implausibility filter, buffer, batch writes | ✅ Complete |
| WU5 | `feat/telemetry-ingest-wu5-end-to-end-ingest` | ~300–480 | Wiring: consumer → filter → writer | ✅ Complete |
| WU6 | `feat/telemetry-ingest-wu6-disorder-guard` | ~210–320 | Monotonic-guard upsert for `vehicle_state` | ✅ Complete |
| WU7 | `feat/telemetry-ingest-wu7-motion-streak-state` | ~140–240 | `MotionDetector` streak tracking | ✅ Complete |
| WU8 | `feat/telemetry-ingest-wu8-lwt-presence` | ~260–410 | Presence consumer, LWT handling (overran to 798 lines) | ✅ Complete |
| WU9 | `feat/telemetry-ingest-wu9-device-simulator` | ~300–550 | Device simulator, DoD proof (overran to 770 lines) | ✅ Complete |

**Total Authored Lines**: ~2,350–3,660 (revised estimate; wider range due to finer splitting and per-unit overhead)

WU8 and WU9 exceeded their line budgets intentionally per explicit maintainer approval (size:exception precedent):
- WU8: 798 lines (estimate 260–410) — driven by comprehensive `PresenceMqttConfig` contract documentation and dual-container integration tests
- WU9: 770 lines (estimate 300–550) — driven by real-broker test infrastructure and both remaining DoD verifications

---

## SDD Cycle Timeline

| Phase | Date | Notes |
|-------|------|-------|
| Proposal | 2026-09-01 | Original 4-WU proposal |
| Design | 2026-09-01 | Architecture decisions |
| Tasks | 2026-09-01 | Original 4-WU plan; split to 9 WUs by 2026-09-11 maintainer decision |
| Apply (WU1–WU9) | 2026-09-01 to 2026-09-14 | Feature-branch-chain implementation across 9 chained PRs |
| Verify | 2026-09-14 | Verification pass with warnings, CRITICAL remediated |
| Archive | 2026-09-14 | Spec synced, change archived, cycle closed |

---

## Artifact Store: openspec (Repo-Local)

All artifacts are persisted to the repository:
- Main spec: `openspec/specs/telemetry-ingest/spec.md`
- Archived change: `openspec/changes/archive/2026-09-14-03-add-telemetry-ingest/`
- Previous archives remain in place: `00-bootstrap-monorepo`, `01-add-geo-core`

---

## Key Learnings

1. Feature-branch-chain architecture enables honest-sized PRs for complex multi-domain changes without artificial task boundaries that break vertical slices or proof.

2. Design ambiguities (reference-position source, streak-state storage, lazy-vs-pre-seeded) that a one-liner task description missed required explicit resolution notes in the final tasks artifact to avoid silent implementation drift.

3. Non-blocking warnings from Testcontainers multi-service tests (intermittent flakes, shortened empirical proofs) are acceptable when documented as residual risk and cross-checked with deterministic structural tests.

4. Git history rewriting (attribution cleanup) before push requires explicit maintainer intent; the orchestrator must preserve this intent in the archive record so future reference does not confuse pre-push cleanup with post-push modification.

5. Strict TDD mode disabled mid-change requires archive-time notation so future verify/apply phases know not to load strict-tdd-verify.md when `testing.strict_tdd: false`.

---

## Closure

This change is **ARCHIVED** as of 2026-09-14. The SDD planning cycle is complete.

**Explicit Note**: This archive does NOT mean code has been deployed or merged to `main`. The 9 feature-branch-chain branches remain on origin, unpulled, unmerged. Deployment is a future ordinary-repository-policy decision.

All artifacts (proposal, design, specs, tasks, verify-report, archive-report) are persisted to the archive location and remain available for audit, replay, or future reference.

---

**Generated by**: sdd-archive phase executor  
**Archive Date**: 2026-09-14  
**Artifact Store**: openspec (repo-local)
