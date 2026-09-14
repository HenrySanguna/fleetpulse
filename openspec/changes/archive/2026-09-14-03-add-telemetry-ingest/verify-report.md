```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:954220a5186662ecc4edc3f7fba7acd8c88499576dafa136d042b0d98591ecdc
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 6/6
scenarios: 10/10
test_command: "cd backend && ./gradlew.bat :domain:test :geo-core:test :api:test :processor:test verifyBackendDependencyDirection --console=plain --rerun"
test_exit_code: 0
test_output_hash: sha256:aae172df7acb5aa0cf3d51704063ecf05959935740c96d78c979b8a72afbbf3c
build_command: "cd backend && ./gradlew.bat :domain:assemble :processor:assemble --console=plain --rerun"
build_exit_code: 0
build_output_hash: sha256:6c28758f91357719b065b25d25476b6e686c46caebf008bacda2247021319653
```

## Verification Report

Change: 03-add-telemetry-ingest
Branch verified: feat/telemetry-ingest-wu9-device-simulator at fc11c29 (cumulative WU1-WU9 plus the late-subscriber remediation test, chained off main) -- confirmed via `git rev-parse HEAD` (fc11c29af2d0af11d4f68ee4da74aca24a3711a5) and `git status --short` (only untracked `.playwright-mcp/` and this change's own `verify-report.md`, zero tracked-file drift)
Mode: Standard (`openspec/config.yaml` `testing.strict_tdd` is `false`, disabled 2026-09-14 by explicit maintainer request per `strict_tdd_disabled_note`). Per this skill's own Decision Gate table ("Strict TDD false or no runner -> Standard verify; skip TDD checks") and the Hard Rule ("If Strict TDD is active, load strict-tdd-verify.md; if inactive, never load it"), `strict-tdd-verify.md` was NOT loaded this pass -- the strict-mode-only sections from the prior two passes (TDD Cycle Evidence table, Test Layer Distribution, Changed File Coverage, Quality Metrics) are intentionally absent here, not omitted by oversight.
Pass type: RE-VERIFY after a test-only remediation closing the prior pass's sole CRITICAL (spec requirement 4's untested late-subscriber-after-disconnect scenario).

### Summary of this re-verify's outcome

The remediation is genuine and independently confirmed. This pass read `PresenceEndToEndTest.java` directly (not just the remediation report) and confirms a new test method, `aClientSubscribingAfterTheWillFiredImmediatelyReceivesTheRetainedOfflinePayload`, that: (1) connects a device with a registered will, announces it online, and waits for `vehicle_state.online=true`; (2) triggers the will via `device.disconnectForcibly(0L, 0L, false)` -- the identical mechanism tests 6.6/6.7 already use -- and waits for `vehicle_state.online=false`, proving the will fired and the presence consumer wrote the offline state; (3) only THEN constructs a brand-new, separate `MqttClient` (`fleetpulse-test-late-subscriber-<uuid>`) and subscribes to the exact same status topic, asserting via `await()` that it receives the retained `{"online":false}` payload. This is a direct, real-broker, runtime test of exactly the scenario the prior pass found uncovered -- a subscriber that was never listening when the will fired still receives the last-known state immediately, via MQTT retain semantics, not test timing. This is not inferred from the remediation narrative alone: the test source was read end-to-end in this session.

Independent re-execution this session (not trusting the remediation report's own numbers):
- `cd backend && ./gradlew.bat :processor:test --tests "*PresenceEndToEndTest" --console=plain --rerun` -> BUILD SUCCESSFUL in 39s. JUnit XML confirms `tests="3" skipped="0" failures="0" errors="0"`, including the new method at 8.625s.
- A full backend re-run was also performed this pass (not strictly required per this task's instructions once the targeted test passed, but chosen for unambiguous full-spec evidence given the verdict is moving to a pass state): `cd backend && ./gradlew.bat :domain:test :geo-core:test :api:test :processor:test verifyBackendDependencyDirection --console=plain --rerun` -> BUILD SUCCESSFUL in 5m13s. Aggregated JUnit XML across all 46 test classes in all 4 modules: 186 tests, 0 failures, 0 errors, 0 skipped (domain 19, geo-core 37, api 47, processor 83 -- processor rose from 82 to 83, the +1 being the new remediation test). `verifyBackendDependencyDirection` passed.
- A transient ERROR-level log burst appeared mid-run from `api`'s `JdbcIndexedSessionRepository` scheduled cleanup task losing its datasource connection during a Testcontainers teardown window -- this is asynchronous scheduled-task log noise unrelated to any test assertion, did not fail any test (all `api` suites report `failures="0" errors="0"`), and is not new: it is normal teardown-timing noise from a background `@Scheduled` bean racing container shutdown, not a defect introduced by or related to this change.

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 30 (27 numbered tasks across 6 sections + 3 "Definicion de terminado" items) |
| Tasks complete | 30 |
| Tasks incomplete | 0 |

### Build & Tests Execution

**Build**: PASSED
```text
cd backend && ./gradlew.bat :domain:assemble :processor:assemble --console=plain --rerun
BUILD SUCCESSFUL in 2s
15 actionable tasks: 5 executed, 10 up-to-date
```

**Tests**: 186 passed / 0 failed / 0 skipped
```text
cd backend && ./gradlew.bat :domain:test :geo-core:test :api:test :processor:test verifyBackendDependencyDirection --console=plain --rerun
BUILD SUCCESSFUL in 5m 13s
domain:    19 tests, 0 failures
geo-core:  37 tests, 0 failures
api:       47 tests, 0 failures
processor: 83 tests, 0 failures  (was 82 before this session's remediation test)
verifyBackendDependencyDirection: PASSED
```

Targeted re-run of the remediated file alone (independent confirmation, not reused from the remediation report):
```text
cd backend && ./gradlew.bat :processor:test --tests "*PresenceEndToEndTest" --console=plain --rerun
BUILD SUCCESSFUL in 39s
PresenceEndToEndTest: tests=3 skipped=0 failures=0 errors=0
  - abruptDisconnectionPublishesTheWillAndMarksVehicleOffline()          6.06s
  - reconnectionMarksVehicleOnlineAgain()                                6.47s
  - aClientSubscribingAfterTheWillFiredImmediatelyReceivesTheRetainedOfflinePayload()  8.63s
```

**Coverage**: Not available -- Jacoco is not configured for `processor`/`domain`, unchanged since prior passes. Not applicable to this evidence gate.

### Spec Compliance Matrix (6 requirements / 10 scenarios)

| # | Requirement | Scenario | Test | Result |
|---|---|---|---|---|
| 1 | Deduplicacion de telemetria reenviada | Reenvio del mismo mensaje | `TelemetryEndToEndIngestTest.sameMessageProcessedTwiceProducesExactlyOneRow` + `TelemetryBatchWriteTest.writerSilentlyDropsAResentDuplicateViaOnConflictDoNothing` | COMPLIANT |
| 1 | Deduplicacion de telemetria reenviada | Rafaga de reenvio tras recuperar cobertura, solapamiento parcial | `TelemetryEndToEndIngestTest.burstOfOneThousandDistinctPositionsLandsWithoutDuplicatesOrDrops` (all-new 1,000-row burst); composed with the exact-duplicate test above rather than one single mixed-batch test | COMPLIANT by composition -- see SUGGESTION 1 |
| 2 | Tolerancia a telemetria desordenada | Posicion antigua llega despues de una reciente | `TelemetryVehicleStateGuardTest.staleMessageAfterARecentOneDoesNotRegressVehicleState` + companion `...IsStillPersistedInPositions` | COMPLIANT |
| 2 | Tolerancia a telemetria desordenada | Posicion mas reciente actualiza el estado | `TelemetryVehicleStateGuardTest.newerMessageAfterAnOlderOneAdvancesVehicleState` | COMPLIANT |
| 3 | Descarte de posiciones implausibles | Salto imposible por error de GPS | `TelemetryBatchWriteTest.implausiblePositionDiscardedByTheFilterIsNeverPersisted` + `TelemetryImplausibilityFilterTest.discardsAPositionThatImpliesAPhysicallyImpossibleSpeed` (counter assertion included) | COMPLIANT |
| 4 | Deteccion de desconexion de dispositivo | Perdida abrupta de conexion del dispositivo | `PresenceEndToEndTest.abruptDisconnectionPublishesTheWillAndMarksVehicleOffline` | COMPLIANT |
| 4 | Deteccion de desconexion de dispositivo | Reconexion del dispositivo | `PresenceEndToEndTest.reconnectionMarksVehicleOnlineAgain` | COMPLIANT |
| 4 | Deteccion de desconexion de dispositivo | Cliente que se suscribe despues de la desconexion | `PresenceEndToEndTest.aClientSubscribingAfterTheWillFiredImmediatelyReceivesTheRetainedOfflinePayload` (new this session) -- device announces online, will is triggered via `disconnectForcibly(0,0,false)`, offline write confirmed, THEN a fresh subscriber connects and receives the retained offline payload | COMPLIANT (was UNTESTED/CRITICAL in the prior pass -- now closed) |
| 5 | Resistencia del consumidor a mensajes malformados | Mensaje con payload invalido | `TelemetryMqttConsumerTest.malformedPayloadIsDiscardedAndSubsequentValidMessagesAreStillProcessed` | COMPLIANT |
| 6 | Consultas historicas sobre datos particionados | Consulta de historico acotada por rango | `PgPartmanPartitionMaintenanceTest.historyQueryForOneVehicleAndDateRangeNeverDoesASequentialScan` | COMPLIANT |

**Compliance summary**: 10 of 10 scenarios have a direct, passing, runtime-covering test -- up from 9/10 in the prior pass. The remediation test closes the sole remaining gap; no other scenario's evidence changed.

### Correctness (Design Decisions vs. Code)

No unexplained design deviations. `tasks.md` documents three deliberate resolutions of design.md ambiguity, each cross-checked directly against the code read this session:
- Implausibility reference-position source: `TelemetryImplausibilityFilter` keeps its own in-memory last-accepted-position map rather than reading `vehicle_state.location` (nothing wrote that column yet at the point this filter runs in the pipeline).
- `vehicle_state` lazy upsert vs. pre-seeded: a single guarded `INSERT ... ON CONFLICT ... WHERE` in `JdbcTelemetryPositionWriter.writeBatch` both creates and monotonically guards the row.
- `MotionDetector` streak-state storage: persisted into `vehicle_state.motion_state` plus two new nullable streak-start-timestamp columns, computed by the pure `VehicleMotionStreakTracker`.

None break any spec requirement. Unchanged since the prior two passes -- no source file was touched by this session's remediation besides the one new test method.

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| Dual MQTT consumers (telemetry QoS 0, presence QoS 1), independent packages | Yes | `dev.fleetpulse.processor.telemetry` and `dev.fleetpulse.processor.presence` remain fully decoupled, no shared types |
| Batch writes via `JdbcTemplate.batchUpdate`, never JPA, never one INSERT per message | Yes | `JdbcTelemetryPositionWriter` confirmed unchanged |
| Partition maintenance invoked explicitly via `@Scheduled`, never pg_partman's background worker | Yes | `PartitionMaintenanceTask` confirmed unchanged |
| Device simulator is a plain-Java tool, not part of the Spring-managed `processor` runtime | Yes | `DeviceSimulatorMain` has its own `main()`, not `@SpringBootApplication`/component-scanned |

### Definicion de terminado (3 items)

| Item | Status | Notes |
|------|--------|-------|
| Particiones de la semana siguiente existen antes de que empiece esa semana | COMPLIANT | `PgPartmanPartitionMaintenanceTest`, unchanged |
| 50 vehiculos durante 10 minutos sin crecimiento de memoria | PARTIALLY VERIFIED -- non-blocking, see WARNING 2 | Verified via a deterministic structural test plus a real-but-shortened (75s, not literal 10 minutes) empirical soak; unchanged this pass, not re-measured per this task's instructions |
| Cortar el simulador de golpe marca esos vehiculos como offline | COMPLIANT | Reuses the same broker LWT mechanism now proven three ways (6.6, 6.7, and the new late-subscriber test) |

### Issues Found

**CRITICAL**: None. The prior pass's sole CRITICAL (spec requirement 4's untested late-subscriber scenario) is resolved -- verified by direct source read of the new test method plus two independent re-executions (targeted 3/3 and full-suite 186/186) in this session, not by trusting the remediation report alone.

**WARNING** (2 -- carried forward unchanged from the prior pass; reconfirmed, nothing about either changed this session):
1. `DeviceSimulatorFleetIntegrationTest.connectingAVehiclePublishesARetainedOnlineAnnouncement` was observed failing twice across 5 full/multi-module runs in an earlier session, always passing in isolation. This pass's own full multi-module run happened to pass it cleanly (4/4) -- a single additional data point, not a re-measurement campaign, and it does not retract the previously observed intermittent failures. Still recommend documenting it as a second known-flaky test alongside `ProcessorHeartbeatPublisherTest` and considering Testcontainers resilience tuning for the `processor` module as a follow-up.
2. The "50 vehicles, 10 minutes, no memory growth" DoD item was verified via a deterministic structural test plus a real-but-shortened (75 seconds, not 10 real minutes) empirical soak. Reasonable substitute evidence, but not literal proof of the DoD wording as stated. Residual, documented, low-severity risk, unchanged.

**SUGGESTION** (1 -- carried forward unchanged):
1. The "burst of overlapping resends" scenario is proven only by composition of two separate tests (exact-duplicate rejection plus an all-new 1,000-row burst), not by one test that mixes both in a single flush batch. Very low-risk gap given `ON CONFLICT DO NOTHING` is per-row and order-independent; one combined test would close it literally. Still open, unchanged.

### Verdict
PASS WITH WARNINGS

The prior pass's sole CRITICAL -- spec requirement 4's "Cliente que se suscribe despues de la desconexion" scenario having no direct, runtime-covering test -- is resolved. This session read `PresenceEndToEndTest.aClientSubscribingAfterTheWillFiredImmediatelyReceivesTheRetainedOfflinePayload` directly and confirms it genuinely exercises the exact scenario: trigger the will, confirm the consumer wrote offline, then connect a fresh subscriber and assert it receives the retained offline payload. Independently re-executed twice this session (targeted 3/3, full-suite 186/186, 0 failures, 0 errors, 0 skipped across all four backend modules) rather than trusting the remediation report's own numbers. Spec compliance is now 10/10 scenarios with a direct, passing, covering test (up from 9/10). All 30 tasks remain complete, no design deviation breaks any spec requirement, and this pass ran as Standard verify (not Strict TDD) per `openspec/config.yaml` `testing.strict_tdd: false`, consistent with this skill's own hard rule to never load the strict module when the project has it disabled.

The verdict is PASS WITH WARNINGS, not a clean PASS, because 2 non-blocking WARNINGs remain open and unchanged: a known-intermittent Testcontainers test under full-module runs, and a DoD memory-growth check proven via a shortened soak rather than the literal 10-minute wording. Neither blocks archive; both are documented, low-severity, pre-existing residual risks with recommended follow-ups, not regressions introduced by this remediation.

Next recommended: sdd-archive.
