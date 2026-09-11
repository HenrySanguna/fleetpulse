# Archive Report: Add Geo Core

**Change**: 01-add-geo-core  
**Archived**: 2026-09-10  
**Archive Path**: `openspec/changes/archive/2026-09-10-01-add-geo-core/`  
**Artifact Store**: openspec (repo-local)

## Executive Summary

The `01-add-geo-core` change has been fully planned, implemented, verified, and archived. All 27 implementation tasks are complete. The geo-core library provides pure Java geospatial calculations (distance, bearing, speed, motion state detection, geofence transitions, and track simplification) with zero Spring/JPA/IO dependencies, enabling testable, reusable logic for FleetPulse's core location intelligence.

## Completion Status

### Task Completion Gate
- **Total Tasks**: 27
- **Completed**: 27 (100%)
- **Status**: ✅ All tasks marked as complete in persisted `tasks.md`

### Verification Status
- **Verdict**: PASS (second independent remediation-follow-up pass)
- **Critical Findings**: 0
- **Warning Findings**: 0
- **Suggestion Findings**: 2 (non-blocking)
- **Requirements Met**: 5/5
- **Scenarios Covered**: 12/12
- **Test Execution**: 37/37 tests passing (~0.39s total runtime)
- **Code Coverage**: 100% branch/line/instruction (Jacoco verified per-class)

## Archive Contents

### Artifacts
- ✅ `proposal.md` — Intent, scope, and approach
- ✅ `specs/geo-core/spec.md` — Delta spec (now synced to main specs)
- ✅ `design.md` — Public API and design decisions
- ✅ `tasks.md` — 27/27 tasks marked complete
- ✅ `verify-report.md` — Full verification evidence (PASS verdict)

### Implementation Summary
- **Production Classes**: 8
  - `GeoPoint.java` (record)
  - `Geo.java` (static methods: distanceMeters, bearingDegrees, speedKmh, isImplausible, simplifyTrack)
  - `MotionState.java` (enum)
  - `MotionConfig.java` (record)
  - `MotionDetector.java` (static motion state transitions)
  - `MotionSample.java` (record)
  - `FenceTransition.java` (enum)
  - `CircleGeofence.java` (circular containment)

- **Test Classes**: 9
  - `GeoPointTest` (1 test)
  - `GeoTest` (11 tests covering distance, bearing, speed, implausibility)
  - `MotionStateTest` (1 test)
  - `MotionSampleTest` (1 test)
  - `MotionConfigTest` (2 tests)
  - `MotionDetectorTest` (10 tests covering hysteresis and transitions)
  - `FenceTransitionTest` (4 tests covering entry/exit/none scenarios)
  - `CircleGeofenceTest` (3 tests)
  - `GeoSimplifyTrackTest` (4 tests)

### Location
- **Module**: `backend/geo-core`
- **Sources**: `backend/geo-core/src/main/java/dev/fleetpulse/geocore/`
- **Tests**: `backend/geo-core/src/test/java/dev/fleetpulse/geocore/`
- **Build**: Gradle, zero dependencies beyond Java standard library

## Specification Compliance

### Requirements Coverage (5/5)
1. **Cálculo de distancia entre coordenadas geográficas** — Full haversine implementation handling antimeridian correctly
2. **Rechazo de velocidades derivadas de instantes inválidos** — speedKmh returns OptionalDouble.empty() for non-monotonic times
3. **Estabilidad del estado de movimiento frente a deriva del GPS** — Asymmetric hysteresis prevents oscillation
4. **Detección de transiciones de geocerca** — Pure function of (wasInside, isInside)
5. **Filtrado de posiciones implausibles** — Identification only, no correction/interpolation

### Scenarios Coverage (12/12)
- Distancia entre dos puntos conocidos → GeoTest#distanceOneDegreeOfLatitudeApartMatchesTheKnownReferenceValue
- Distancia cruzando el antimeridiano → GeoTest#distanceAcrossTheAntimeridianIsTheShortWayAround
- Distancia de un punto a sí mismo → GeoTest#distanceFromAPointToItselfIsZero
- Instantes invertidos → GeoTest#speedIsEmptyWhenTheSecondInstantIsBeforeTheFirstBecauseADeviceResentBufferedData
- Instantes idénticos → GeoTest#speedIsEmptyWhenTheSecondInstantIsEqualToTheFirst
- Vehículo aparcado con deriva de GPS → MotionDetectorTest#aParkedVehicleWithRealisticGpsDriftNeverTransitionsAwayFromStopped
- Arranque real del vehículo → MotionDetectorTest#aRealStartupThatStaysAboveTheStartThresholdLongEnoughTransitionsToMoving
- Entrada en geocerca → FenceTransitionTest#reportsEnteredWhenVehicleWasOutsideAndIsNowInside
- Salida de geocerca → FenceTransitionTest#reportsExitedWhenVehicleWasInsideAndIsNowOutside
- Permanencia dentro sin evento → FenceTransitionTest#reportsNoneWhenVehicleStaysInside
- Salto imposible por error de GPS → GeoTest#anImpossibleJumpOverAFewSecondsIsMarkedImplausible
- Desplazamiento rápido pero plausible → GeoTest#aFastButAchievableMoveIsNotMarkedImplausible

## Known Deviations

### MotionSample Fields Extension
**Issue**: Design.md pseudocode describes `MotionDetector.next` as a memoryless pure function that produces the next state, but cannot express "N consecutive seconds below threshold" without state.

**Resolution**: `MotionSample` record was extended with fields not shown in design.md:
- `lowSpeedStreak: Duration` — tracks consecutive time below stop threshold
- `highSpeedStreak: Duration` — tracks consecutive time above start threshold

These fields allow stateful counting while keeping `MotionDetector.next` itself pure (static, no mutable fields). The function takes explicit previous state via `sample` parameter, making it fully testable without Spring context.

**Status**: Documented in verify-report.md as an intentional, justified elaboration. The shipped code matches the tested behavior; future readers should check apply-progress observation or this archive report for the field set.

## Final-State Authority

This archive report describes the state of `01-add-geo-core` AT CLOSE per the following hierarchy (from `sdd-archive/SKILL.md`):

1. **Persisted Tasks Artifact** (highest authority): `tasks.md` shows 27/27 complete ✅
2. **Launch Prompt Final-State Facts**: "implementation is complete", "all 27 tasks complete", "100% coverage", "37/37 tests passing", "PASS verdict with 0 CRITICAL"
3. **Intermediate Snapshots** (verify-report, apply-progress): Evidence of state during verification phase

**No contradictions found**. All sources agree on final state: implementation complete, all verification passed, archive ready.

## Specs Synced to Main

| Domain | Action | Details |
|--------|--------|---------|
| geo-core | Created | Delta spec copied to `openspec/specs/geo-core/spec.md` (5 ADDED requirements, 12 scenarios) |

## Mechanical Archive Operations

### Spec Copy Verification
```
Source: openspec/changes/01-add-geo-core/specs/geo-core/spec.md
Destination: openspec/specs/geo-core/spec.md
Diff Result: (empty — files identical)
Status: ✅ PASS
```

### Folder Move Verification
```
Source Snapshot: /tmp/tmp.f0S9LFTtmj/source (created before operations)
Destination: openspec/changes/archive/2026-09-10-01-add-geo-core
Copy: ✅ Completed
Source Removal: ✅ Completed
Diff Comparison: (empty — content preserved exactly)
Status: ✅ PASS
```

## Observations and Traceability

### Engram Observations Referenced
- `sdd/01-add-geo-core/apply-progress` (id 80) — Detailed TDD Cycle Evidence table
- `sdd/01-add-geo-core/verify-report` (id 81) — Full verification evidence and remediation history

### Archive Observation
This archive report is persisted as `openspec/changes/archive/2026-09-10-01-add-geo-core/archive-report.md` (filesystem-based, openspec mode).

## Key Learnings

1. Asymmetric hysteresis (separate thresholds for start and stop) is essential for stable motion detection over noisy GPS data.
2. Haversine's squared sine term naturally handles antimeridian crossings without special cases.
3. Returning `OptionalDouble` for speed (rather than null sentinel or exception) forces callers to handle non-monotonic timestamps at the call site, preventing silent failures downstream.
4. Geospatial tests benefit from explicit tolerance (`assertThat(...).isCloseTo(..., offset(...))`) instead of exact equality, due to floating-point precision.
5. A pure-function geospatial library (zero I/O, zero Spring) enables comprehensive unit testing at millisecond-scale performance, critical for a GPS-heavy application.

---

**Archive completed**: 2026-09-10 at end of SDD cycle  
**Status**: Ready for integration and deployment  
**Next step**: User decides on commit/push to repository
