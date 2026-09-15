# Tasks: Add Geofencing

## Review Workload Forecast

**Decision needed before apply: No — resolved automatically 2026-09-14 (delivery_strategy=auto-chain)**
**Chained PRs recommended: Yes**
**400-line budget risk: High**
**Chain strategy: feature-branch-chain — tracker `feat/geofencing`, each work-unit branch off the immediately previous one**

This forecast was produced by `sdd-apply` on first launch, not by `sdd-tasks` (this change's `tasks.md` predates the Review Workload Forecast convention, same precedent as `02-add-fleet-auth`, `03-add-telemetry-ingest`, and `04-add-live-map`). No code was written before this forecast. Per the session's `delivery_strategy=auto-chain`, the split below was applied automatically without stopping to ask — same authority `03-add-telemetry-ingest` and `04-add-live-map` used.

### Why this change is high risk for the 400-line budget

- 25 numbered tasks across 6 sections plus 2 "Definición de terminado" items, spanning a new PostGIS-backed schema, a processor-side evaluation pipeline (single spatial query + set-comparison enter/exit derivation), genuinely new geo-core oscillation-damping logic (not yet scaffolded — unlike `CircleGeofence`/`FenceTransition`, which already exist idle in `geo-core` since change `01-add-geo-core`), rule-driven alert dispatch over a new QoS 2 MQTT topic, a backend CRUD API for geofences (implied by task 5.2 but not listed as its own task — see gap below), a MapLibre polygon/circle drawing editor, and 9 Testcontainers tests including a realistic-noise oscillation test explicitly called out in the DoD.
- **Gap discovered, not in the original task list**: task 5.2 ("CRUD de geocercas con validación de geometría") assumes a backend API for geofences exists for the console to call, but no section in this file lists backend CRUD endpoints (`POST/GET/PUT/DELETE /api/geofences`) as their own task — only the console-side task references it. Resolved the same way `04-add-live-map`'s WU2 resolved its own missing-track-endpoint gap: folded into a dedicated backend-CRUD work unit (WU6 below) rather than leaving 5.2 unimplementable, documented here per the apply skill's "note deviations, don't silently freelance" rule.
- **Architecture decision, not in the original task list**: task 2.4 ("evaluar solo si el mensaje es más reciente") must follow "the same pattern as telemetry-ingest's task 4.2" per the launch prompt. Task 4.2 (change 03) applies `MotionDetector` inside `JdbcTelemetryPositionWriter.writeBatch`'s existing guarded upsert, not as a separate check — the SQL `WHERE recorded_at < excluded.recorded_at` guard is what actually decides "is this message newer", and any per-message computation ahead of it (there, `VehicleMotionStreakTracker`; here, geofence evaluation) is simply discarded if the guard rejects the row. Geofence evaluation is resolved to follow the identical shape: extended into the same `JdbcTelemetryPositionWriter` write path (processor), not a separate consumer or a second guard, so "evaluate only if newer" costs no new comparison logic beyond what task 4.1 (change 03) already established.
- Oscillation damping (section 3) is, per proposal.md itself, "donde está el valor del producto y donde se concentran los casos borde" — the two-part solution (temporal confirmation with `pending_since`, plus an asymmetric `ST_Buffer` exit margin, mirroring `MotionConfig`'s asymmetric start/stop thresholds) is genuinely new `geo-core` code requiring the module's non-negotiable 100% branch coverage (`openspec/config.yaml`), not an extension of `FenceTransition` (which only classifies ENTERED/EXITED/NONE from two booleans, with no timing or confirmation concept at all).
- Nearly every Testcontainers test in section 6 (all except 6.9, the GiST plan test) can only honestly prove its scenario once evaluation (section 2), damping (section 3), and alert dispatch (section 4) are wired together through the real processor pipeline — the same "end-to-end wiring is its own work unit" shape `03-add-telemetry-ingest`'s WU5 (idempotency) and WU8 (LWT presence) already established, not something any single section can prove alone.
- No polygon/circle drawing library (`maplibre-gl-draw` or equivalent) is in `package.json` yet — `apps/console` only has `maplibre-gl` itself (from change 04). The drawing editor (5.1) will need either a new dependency or hand-rolled click-to-vertex drawing on top of the vanilla MapLibre GL JS API `04-add-live-map` already established (no DOM markers, GeoJSON source + layer only) — a real design decision for that work unit, not a mechanical task.

### Rough line-count estimate (additions + deletions, authored code; lockfile/generated api-client diffs excluded from the authored-risk count, consistent with `sdd-phase-common.md`'s generated-goldens exclusion)

| Section | Scope | Estimate |
|---|---|---|
| 1. Modelo | `geofences` + `vehicle_fence_state` tables (Flyway), GiST index, rule/dwell_secs constraints, circle-as-buffered-polygon convention proven at the schema level, Testcontainers schema test | ~250–350 |
| 2. Evaluación | Single native spatial query (all active geofences containing the point), set-comparison enter/exit derivation, `FenceTransition` reuse (already exists, zero new geo-core code here), GiST execution-plan test, overlapping-geofences test | ~350–500 |
| 3. Amortiguación de oscilación | New `geo-core` pure damping logic (temporal confirmation config/evaluator, asymmetric buffer decision), 100% branch coverage unit tests — genuinely new code, no existing scaffold to extend | ~400–600 |
| 4. Reglas y alertas + wiring | Extends `JdbcTelemetryPositionWriter`'s guarded write path with geofence evaluation (per the task-2.4 architecture decision above), `on_enter`/`on_dwell`/`on_exit` rule dispatch, dwell timer state, `fleet/{orgId}/alerts` QoS 2 publisher + topic ACL registration (mirroring `02-add-fleet-auth`'s dynsec pattern), alerts persistence table + writer | ~500–700 |
| 5. Tests de extremo a extremo | 6 dual-container (PostGIS + Mosquitto) Testcontainers tests proving the wired pipeline: clean enter/exit, the realistic-noise oscillation trace (explicit DoD requirement), overlap, stale-telemetry no-retroactive-alerts, `on_dwell`, restart-preserves-state | ~550–800 |
| 6. Editor: CRUD backend (gap above) | `GeofenceController`/`GeofenceService`/DTOs, geometry validation (closed polygon, no self-intersection), org-scoped Testcontainers integration test, `libs/api-client` regeneration (excluded from count) | ~450–650 |
| 7. Editor: consola MapLibre | Polygon/circle drawing on the existing MapLibre map, active-geofence visualization layer, wiring to the CRUD backend (WU6) | ~450–650 |

**Total estimate: ~2,950–4,250 changed lines.** Every section individually approaches or exceeds the 400-line budget; this is not a borderline case, matching `03-add-telemetry-ingest`'s and `04-add-live-map`'s precedent for a backend+frontend greenfield vertical slice of comparable scope.

### Finalized work units (feature-branch-chain)

PR #1 targets `feat/geofencing` (the tracker branch, created off `main`); each later PR targets the immediately previous PR's branch; the tracker branch aggregates to `main` once all slices land.

| Work unit | Task IDs covered | Line estimate | Branch | Builds on |
|---|---|---|---|---|
| WU1 — Modelo | 1.1, 1.2, 1.3 | ~250–350 | `feat/geofencing-wu1-schema` | Tracker only. Pure DDL + schema-level proof; no evaluation/CRUD code exists yet to exercise instead. |
| WU2 — Evaluación | 2.1, 2.2, 2.3 + test 6.9 (GiST plan) + test 6.5 (geocercas solapadas) | ~350–500 | `feat/geofencing-wu2-evaluation-query` | WU1. A standalone, directly-callable evaluator (single query + set comparison + `FenceTransition`) proven with a JdbcTemplate-level test harness inserting rows directly — no MQTT/processor wiring needed to prove the query itself. |
| WU3 — Amortiguación de oscilación | 3.1, 3.2, 3.3 | ~400–600 | `feat/geofencing-wu3-oscillation-damping` | WU1 (branch lineage only). Pure `geo-core` code, same shape `MotionDetector`/`MotionConfig` already established for motion-state hysteresis — provable entirely with unit tests, no database or broker needed. |
| WU4 — Reglas y alertas + wiring | 2.4, 4.1, 4.2, 4.3 | ~500–700 | `feat/geofencing-wu4-rules-and-alerts` | WU2 (evaluator) + WU3 (damping) — wires both into `JdbcTelemetryPositionWriter`'s existing guarded write path (the task-2.4 architecture decision above), adds rule dispatch, dwell timer, alert persistence, and the QoS 2 MQTT publisher + ACL. The single hardest, most cohesive vertical slice — likely to land above budget as one honest unit, matching `03-add-telemetry-ingest`'s WU4/WU8 and `04-add-live-map`'s WU2/WU3/WU4 precedent for "still above budget as one cohesive slice, `size:exception` expected". |
| WU5 — Tests de extremo a extremo | 6.1, 6.2, 6.3, 6.4, 6.6, 6.7, 6.8 + both DoD items | ~550–800 | `feat/geofencing-wu5-e2e-tests` | WU4 — every one of these scenarios needs evaluation + damping + alerting wired together through the real dual-container pipeline to prove honestly, the same "end-to-end wiring is its own work unit" shape `03-add-telemetry-ingest`'s WU5/WU8 already established. |
| WU6 — Editor: CRUD backend | 5.2 (backend half, gap above) | ~450–650 | `feat/geofencing-wu6-crud-api` | WU1 (schema) — pure backend controller/service/DTO work against `geofences`, no coupling to the processor evaluation pipeline. |
| WU7 — Editor: consola MapLibre | 5.1, 5.3 + 5.2 (console half) | ~450–650 | `feat/geofencing-wu7-console-editor` | WU6 — the drawing editor persists through the CRUD backend WU6 built, and visualization reads the same active-geofences list. |

**WU1 (Modelo) is done** — see the resolution note next to tasks 1.1–1.3 below. Final authored diff: 342 lines across 2 new files (`V8__add_geofences.sql`, `GeofenceSchemaTest.java`), within the ~250–350 estimate.

**WU2 (Evaluación) is done** — see the resolution notes next to tasks 2.1–2.3 and tests 6.5/6.9 below. Final authored diff: 472 lines across 3 new files (`GeofenceEvaluator.java`, `GeofenceTransitionResult.java`, `GeofenceEvaluatorTest.java`), within the ~350–500 estimate. Genuine design gap found and resolved during implementation: design.md's `ST_Contains(area::geometry, ...)` sketch cannot use the geography-typed GiST index at all (test 6.9 failed with a real `Seq Scan` on first run) — fixed with `ST_Covers(area, point::geography)`, one of the two predicates the WU2 launch scope explicitly allowed.

## 1. Modelo
- [x] 1.1 Migración Flyway: tabla `geofences` con `geography(Polygon, 4326)` e índice GiST — **WU1**: `V8__add_geofences.sql` (`backend/domain/src/main/resources/db/migration/`). Deviates from design.md's SQL sketch in naming only, not shape: `org_id` → `organization_id` (matches `vehicles.organization_id`, V3) and adds `created_at` (every other org-scoped entity table already has one; design.md's sketch just omitted it). `rule`/`dwell_secs` get explicit CHECK constraints (`chk_geofences_rule`, `chk_geofences_dwell_secs`) enforcing the three allowed rule values and that `dwell_secs` is populated if-and-only-if `rule = 'on_dwell'`, mirroring `mqtt_credentials.chk_mqtt_credentials_single_owner`'s (V3) established pattern of encoding a field's conditional relevance in the schema itself. `GeofenceSchemaTest` (Testcontainers, same recipe as `TelemetrySchemaTest`) proves the GiST index (`USING gist (area)`), both constraints, and a well-formed insert round-trip.
- [x] 1.2 Tabla `vehicle_fence_state` con `is_inside`, `since` y `pending_since` — **WU1**: same migration, composite PK `(vehicle_id, geofence_id)` (proven by `GeofenceSchemaTest.vehicleFenceStateHasCompositePrimaryKeyOnVehicleAndGeofence`), FKs to `vehicles`/`geofences`. No row is pre-seeded for every vehicle × geofence pair — a row is only ever written once a vehicle has actually been evaluated against that geofence (future WU2/WU4 concern, documented in the migration's own comment so the convention is established before any writer exists).
- [x] 1.3 Geocercas circulares almacenadas como polígono (buffer del centro) — **WU1**: no separate schema shape exists for a circle — `area` is always `GEOGRAPHY(Polygon, 4326)`, matching design.md's explicit "un solo camino de evaluación en lugar de dos ramas". This work unit only commits to and proves the convention at the schema/SQL level (`GeofenceSchemaTest.aCircularGeofenceStoredAsABufferedPolygonEvaluatesWithTheSamePredicateAsAPolygon`: `ST_Buffer` a center point, store it exactly like any hand-drawn polygon, confirm `ST_Contains` behaves identically) — the actual write path that computes the buffer at geofence-creation time belongs to the CRUD API (WU6, task 5.2), which does not exist yet.

## 2. Evaluación
- [x] 2.1 Consulta nativa única que devuelve todas las geocercas activas que contienen el punto — **WU2**: `GeofenceEvaluator.CONTAINING_GEOFENCE_IDS_SQL` (`backend/processor/.../geofencing/GeofenceEvaluator.java`). **Deviates from design.md's SQL sketch, documented per the "note deviations" convention**: design.md's sketch used `ST_Contains(area::geometry, ...)`, but `idx_geofences_area` (V8) is a GiST index built directly on the `geography`-typed `area` column — casting to `::geometry` per row makes that index unusable, and the planner silently falls back to a full sequential scan regardless of the index's existence. This was caught empirically: test 6.9 failed with `Seq Scan on geofences` on first run. Fixed by using `ST_Covers(area, point::geography)` instead, one of the two predicates the launch prompt explicitly allowed (`ST_Contains`/`ST_Covers`) — natively index-aware against a geography GiST index. Behavioral difference from `ST_Contains` is boundary inclusion only (a point exactly on the edge counts as covered), acceptable since WU3's oscillation damping absorbs boundary noise regardless of which side a single reading falls on.
- [x] 2.2 Comparación de conjuntos (dentro ahora vs. dentro antes) para derivar entradas y salidas — **WU2**: `GeofenceEvaluator.evaluate()` unions the containment query's result set with `vehicle_fence_state`'s previously-inside set (`is_inside = true` rows for the vehicle), then classifies each relevant geofence.
- [x] 2.3 Uso de `geo-core` para la decisión pura de transición por geocerca — **WU2**: reuses `FenceTransition.from(wasInside, isInside)`, already idle in `geo-core` since change `01-add-geo-core`. Zero new `geo-core` code needed, confirmed by the forecast.
- [ ] 2.4 Evaluar solo si el mensaje es más reciente que el último evaluado para ese vehículo — see the WU4 architecture decision documented in the forecast above: this extends `JdbcTelemetryPositionWriter`'s existing guarded upsert (change 03, task 4.1), the same pattern task 4.2 already established for `MotionDetector`.

## 3. Amortiguación de oscilación
- [ ] 3.1 Confirmación temporal: marcar `pending_since` y confirmar tras N lecturas o T segundos
- [ ] 3.2 Descartar la pendiente si la pertenencia vuelve al estado anterior antes de confirmar
- [ ] 3.3 Margen espacial asimétrico: salida evaluada contra el polígono con buffer

## 4. Reglas y alertas
- [ ] 4.1 Reglas `on_enter`, `on_exit`, `on_dwell` (permanencia superior a `dwell_secs`)
- [ ] 4.2 Publicación de alertas en `fleet/{orgId}/alerts` con QoS 2
- [ ] 4.3 Persistencia de alertas para el histórico y el panel de la consola

## 5. Editor en la consola
- [ ] 5.1 Dibujo de polígonos y círculos sobre MapLibre
- [ ] 5.2 CRUD de geocercas con validación de geometría (polígono cerrado, sin auto-intersección) — backend half folded into WU6 (gap documented in the forecast above); console half in WU7
- [ ] 5.3 Visualización de geocercas activas en el mapa en vivo

## 6. Tests (Testcontainers con PostGIS real)
- [ ] 6.1 Entrada limpia en geocerca genera exactamente una alerta
- [ ] 6.2 Salida limpia genera exactamente una alerta
- [ ] 6.3 **Oscilación en el borde: una traza con deriva sobre el límite NO genera ninguna alerta**
- [ ] 6.4 Una entrada real tras una oscilación SÍ genera alerta
- [x] 6.5 Vehículo dentro de dos geocercas solapadas genera una alerta por cada una — **WU2**: `GeofenceEvaluatorTest.evaluateReportsAnEnteredTransitionForEachOfTwoOverlappingGeofencesEnteredSimultaneously()`. Proven at the evaluator level (query + set comparison + `FenceTransition`), not yet through the full alert-dispatch pipeline (rule dispatch/MQTT publish is WU4's scope) — this test proves the evaluator returns one independent `ENTERED` `GeofenceTransitionResult` per overlapping geofence, not a single merged result.
- [ ] 6.6 Telemetría antigua reenviada NO genera alertas retroactivas
- [ ] 6.7 `on_dwell` dispara al superar el tiempo de permanencia, una sola vez
- [ ] 6.8 Reinicio de `processor` no pierde el estado de pertenencia (está persistido)
- [x] 6.9 El plan de ejecución de la consulta de pertenencia usa el índice GiST, sin recorrido secuencial — **WU2**: `GeofenceEvaluatorTest.containingGeofenceQueryUsesTheGistIndexWithNoSequentialScan()`, same "seed real volume + `ANALYZE` + `EXPLAIN`" recipe as `03-add-telemetry-ingest`'s `PgPartmanPartitionMaintenanceTest.historyQueryForOneVehicleAndDateRangeNeverDoesASequentialScan()` (500 scattered noise geofences + 1 target, `EXPLAIN (ANALYZE, FORMAT TEXT)` against the exact production SQL constant). This test is what caught the `ST_Contains`-vs-`ST_Covers` index-usability gap documented next to task 2.1 — it failed with a real `Seq Scan` before that fix, confirming it genuinely exercises the index rather than passing trivially.

## Definición de terminado
- [ ] El test 6.3 usa una traza con ruido realista sobre el borde, no dos puntos alternos artificiales
- [ ] Un vehículo simulado que cruza una geocerca genera exactamente dos alertas (entrada y salida), ni una más
