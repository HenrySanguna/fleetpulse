# Tasks: Add Telemetry Ingest

## Review Workload Forecast

**Decision needed before apply: No — resolved 2026-09-11**
**Chained PRs recommended: Yes**
**400-line budget risk: Medium (after finer split — two of nine work units still run above 400 lines as cohesive vertical slices; see table below)**
**Chain strategy: feature-branch-chain — tracker `feat/telemetry-ingest`, each work-unit branch off the immediately previous one**

This forecast was produced by `sdd-apply` on first launch, not by `sdd-tasks` (this change's `tasks.md` predates the Review Workload Forecast convention, same precedent as `02-add-fleet-auth`). No code was written before this forecast; `sdd-apply` stopped per the mandatory workload guard, presented three resolution options, and the maintainer chose option (b): a finer chained split closer to the 400-line budget, accepting that some individual PRs will not independently prove end-to-end behavior.

### Why this change is high risk for the 400-line budget

- 30 tasks across 6 sections plus 3 "Definición de terminado" items, spanning native table partitioning + `pg_partman` wiring, a new MQTT consumer with QoS-differentiated adapters, a buffered batch-write path on `JdbcTemplate.batchUpdate`, a disorder-tolerant `vehicle_state` upsert, a second MQTT consumer for LWT presence, a dev-only multi-vehicle simulator, and 10 Testcontainers tests against real PostGIS **and** Mosquitto simultaneously.
- `positions` and `vehicle_state` do not exist yet anywhere in the codebase (checked: no matches in any archived or pending change besides this one). PostGIS and `pg_partman` extensions are already bootstrapped by `V1__init.sql` and proven by the existing `FlywayMigrationTest` — that removes extension-bootstrap work from this estimate — but the partitioned `positions` table, its pre-created weekly partitions, BRIN/GiST indexes, and the `pg_partman` `create_parent`/retention configuration are all still net-new.
- `MotionDetector.next(prev, sample, cfg)` (`geo-core`) is a pure function that takes an explicit previous `MotionState` plus `lowSpeedStreak`/`highSpeedStreak` durations. design.md only says "`MotionDetector` solo se aplica cuando el mensaje es más reciente" — it does not say where those per-vehicle streak durations live between messages. Something in `processor` must track or reconstruct per-vehicle streak state across messages; this is additional design surface beyond what task 4.2's one-line description suggests.
- design.md's implausibility check (task 2.4, `Geo.isImplausible`) needs a "last known position" per vehicle to compare against. `Geo.isImplausible` itself already exists in `geo-core` (zero new code there), but design.md does not say whether that reference position is read from `vehicle_state` (section 4, itself guarded by the disorder-tolerance logic) or tracked separately by the consumer before the batch flush. This is a real implementation decision that couples section 2 to section 4, not two independent tasks.
- Section 6 is 10 Testcontainers tests against two real services (PostGIS + Mosquitto) at once, including a 1,000-row burst-dedup test and an `EXPLAIN`-plan assertion that the histórico query never does a sequential scan. `02-add-fleet-auth`'s 7 broker-only Testcontainers tests already ran ~550–850 authored lines per work unit; dual-service tests with volume and plan assertions are typically heavier, not lighter.
- The dev-only device simulator (task 5.3) is a real multi-client MQTT publisher (N simulated vehicles, periodic telemetry, LWT registration, clean shutdown), and it is also the natural driver for two LWT tests (6.6 abrupt disconnect, 6.7 reconnect) and the "cortar el simulador... offline" DoD item — it is load-bearing test infrastructure, not throwaway tooling.

### Rough line-count estimate (additions + deletions, authored code)

| Section | Scope | Estimate |
|---|---|---|
| 1. Esquema y particionado | `positions` partitioned table + PK + BRIN/GiST indexes, `pg_partman` `create_parent`/retention config, pre-created weekly partitions, `vehicle_state` table, `@Scheduled` maintenance task + properties, migration/partition-existence tests | ~450–550 |
| 2. Consumo MQTT | Spring Integration inbound adapter(s) for `telemetry`/`command`/`alerts` with per-adapter QoS, payload DTO + schema validation, malformed-message counter, implausibility check wiring against `Geo.isImplausible` | ~400–500 |
| 3. Escritura por lotes | In-memory buffer with size/time dual flush trigger, `JdbcTemplate.batchUpdate` with `ON CONFLICT DO NOTHING`, graceful-shutdown flush (`SmartLifecycle`/`@PreDestroy`), batch-size/interval properties | ~300–400 |
| 4. Estado actual tolerante al desorden | Monotonic-guarded `vehicle_state` upsert (`WHERE recorded_at < ?`), per-vehicle `MotionDetector` state/streak tracking applied only on newer messages | ~250–350 |
| 5. Presencia con LWT | Testament contract doc, `fleet/+/vehicle/+/status` consumer updating the online flag, multi-vehicle device simulator (telemetry + LWT registration) | ~350–500 |
| 6. Tests (Testcontainers, PostGIS + Mosquitto reales) | 10 tests: idempotencia, 2× desorden, ráfaga de 1.000 posiciones, implausible, LWT offline, reconexión, plan `EXPLAIN` sin seq scan, payload malformado, apagado con descarga de buffer — más soporte compartido de doble contenedor | ~750–950 |

**Total estimate: ~2,500–3,250 changed lines.** Every individual section is already close to or above the 400-line budget; this is not a borderline case.

### Resolution (2026-09-11)

The maintainer reviewed the original 4-WU proposal below (kept for traceability) and chose a finer chained split: more, narrower work units closer to the 400-line budget, explicitly accepting that a few units will not independently prove end-to-end behavior. Two splits were requested specifically:

- Split section 1 (esquema y particionado) into a pure schema/indexes slice and a separate `pg_partman`/scheduled-maintenance slice — these are two genuinely independent vertical slices (DDL-only vs. operational partition lifecycle).
- Split the oversized original WU2 (consumo MQTT + escritura por lotes, ~1,150–1,450 lines) into at least two units.

Applying both, plus the same reasoning to the original WU4 (LWT + simulator, also ~2x budget), produced **9 work units**. The one dependency the finer split deliberately keeps — rather than forcing an artificial separation — is WU5: idempotency and burst-dedup can only be honestly proven with both the MQTT consumer (WU3) and the batch writer (WU4) wired together and exercised through a real broker, so WU5 stays a distinct "end-to-end wiring" unit that depends on both, instead of duplicating that proof inside WU3 or WU4 alone.

### Finalized work units (feature-branch-chain)

PR #1 targets `feat/telemetry-ingest` (the tracker branch, created off `main`); each later PR targets the immediately previous PR's branch; the tracker branch aggregates to `main` once all slices land.

| Work unit | Task IDs covered | Line estimate | Branch | Builds on |
|---|---|---|---|---|
| WU1 — Esquema e índices | 1.1, 1.2, 1.4 | ~150–220 | `feat/telemetry-ingest-wu1-schema-and-indexes` | Tracker only. Pure DDL (`positions` partitioned table, PK, BRIN/GiST indexes, `vehicle_state` table); no partitions exist yet, so tests assert structure, not inserts. |
| WU2 — `pg_partman` y mantenimiento programado | 1.3, 1.5 + test 6.8 + DoD "particiones de la semana siguiente existen antes de que empiece esa semana" | ~250–370 | `feat/telemetry-ingest-wu2-partition-maintenance` | WU1 — needs the partitioned `positions` table to exist before `create_parent`/retention config and the `@Scheduled` maintenance task have anything to operate on. |
| WU3 — Consumo MQTT | 2.1, 2.2, 2.3 + test 6.9 | ~300–420 | `feat/telemetry-ingest-wu3-mqtt-consumer` | WU2 (branch lineage only). Logically independent of schema: adapters, QoS, payload validation, malformed counter — no persistence yet, so 6.9 (malformed doesn't crash the consumer) is provable here alone. |
| WU4 — Implausibilidad y escritura por lotes | 2.4, 3.1, 3.2, 3.3 + tests 6.5, 6.10 | ~440–650 | `feat/telemetry-ingest-wu4-batch-writer` | WU1 (schema) for the target tables; WU3 for the validated-message shape it buffers. Tests call the filter/buffer/writer directly (no live MQTT needed) to prove implausible-discard and shutdown-flush, so this stays provable without WU3's consumer running live. Still the largest unit — a real vertical slice (filter → buffer → batch insert → shutdown flush) that resists further splitting without breaking cohesion. |
| WU5 — Extremo a extremo: idempotencia y ráfaga | Wiring only (connects WU3's consumer to WU4's filter/writer) + tests 6.1, 6.4 | ~300–480 | `feat/telemetry-ingest-wu5-end-to-end-ingest` | WU3 **and** WU4 — deliberately kept as a real dependency: idempotency and the 1,000-row burst-dedup test can only be honestly proven with the consumer and the batch writer wired together through a real broker, not separately. |
| WU6 — Desorden: guardia de monotonía | 4.1 + tests 6.2, 6.3 | ~210–320 | `feat/telemetry-ingest-wu6-disorder-guard` | WU5 — needs the full ingest pipeline actually persisting positions to prove the old message still lands in `positions` while `vehicle_state` does not move backward. |
| WU7 — Desorden: racha de `MotionDetector` | 4.2 | ~140–240 | `feat/telemetry-ingest-wu7-motion-streak-state` | WU6 — applies `MotionDetector` only when the monotony guard already accepted the message as newer. No section-6 Testcontainers test maps to 4.2 alone; covered by unit tests on the streak-state wiring. |
| WU8 — Presencia con LWT | 5.1, 5.2 + tests 6.6, 6.7 | ~260–410 | `feat/telemetry-ingest-wu8-lwt-presence` | WU1 (`vehicle_state.online` flag) for the target column; branch-wise off WU7. Independent MQTT topic (`status`), no coupling to the telemetry ingest pipeline itself. |
| WU9 — Simulador de dispositivo | 5.3 + DoD "50 vehículos durante 10 minutos sin crecimiento de memoria" + DoD "cortar el simulador... offline" | ~300–550 | `feat/telemetry-ingest-wu9-device-simulator` | WU8 — the offline-on-cut DoD check needs the presence consumer already wired; the memory-growth check needs the full ingest pipeline running underneath the simulated load. |

**Revised total estimate: ~2,350–3,660 lines** across 9 PRs (vs. ~2,500–3,250 across 4 PRs). The wider range versus the original 4-WU estimate is the expected cost of finer slicing — each additional PR boundary adds small per-unit overhead (its own test scaffolding, wiring glue) that a larger merged unit would have shared. WU4 and WU5 are the two units most likely to still land above 400 lines as cohesive, honestly-tested vertical slices; every other unit is at or near budget.

<details>
<summary>Original 4-WU proposal (superseded — kept for traceability)</summary>

1. **WU1 — Esquema y particionado** (tasks 1.1–1.5 + test 6.8 + DoD "particiones de la semana siguiente existen antes de que empiece esa semana"). ~550–700 lines. Superseded by the WU1/WU2 split above.
2. **WU2 — Consumo MQTT + escritura por lotes** (tasks 2.1–2.4 + 3.1–3.3 + tests 6.1, 6.4, 6.5, 6.9, 6.10). ~1,150–1,450 lines. Superseded by the WU3/WU4/WU5 split above.
3. **WU3 — Estado actual tolerante al desorden** (tasks 4.1–4.2 + tests 6.2, 6.3). ~450–600 lines. Superseded by the WU6/WU7 split above.
4. **WU4 — Presencia con LWT + simulador** (tasks 5.1–5.3 + tests 6.6, 6.7 + 2 DoD items). ~700–950 lines. Superseded by the WU8/WU9 split above.

</details>

No implementation code has been written yet. `sdd-apply` resumes from task 1.1 (WU1) under this finalized plan.

## 1. Esquema y particionado
- [ ] 1.1 Migración Flyway: tabla `positions` particionada por rango sobre `recorded_at`, PK `(vehicle_id, recorded_at)`
- [ ] 1.2 Índice BRIN sobre `recorded_at`, GiST sobre `location`
- [ ] 1.3 `pg_partman` configurado con particiones semanales creadas por adelantado y retención configurable
- [ ] 1.4 Tabla `vehicle_state` (última posición, estado de movimiento, online, `recorded_at`)
- [ ] 1.5 Tarea `@Scheduled` que invoca `partman.run_maintenance_proc()` explícitamente

## 2. Consumo MQTT
- [ ] 2.1 Adaptador entrante de Spring Integration suscrito a `fleet/+/vehicle/+/telemetry`
- [ ] 2.2 QoS por adaptador: telemetría 0, comandos 1, alertas 2
- [ ] 2.3 Validación del payload; los mensajes malformados se descartan y se contabilizan, no tumban el consumidor
- [ ] 2.4 Descarte de posiciones implausibles usando `Geo.isImplausible` de `geo-core`

## 3. Escritura por lotes
- [ ] 3.1 Buffer en memoria con descarga por tamaño (N filas) o por tiempo (T ms), lo que ocurra antes
- [ ] 3.2 `JdbcTemplate.batchUpdate` con `ON CONFLICT (vehicle_id, recorded_at) DO NOTHING` — **no usar JPA para esto**
- [ ] 3.3 Descarga del buffer en el apagado ordenado del contexto de Spring

## 4. Estado actual tolerante al desorden
- [ ] 4.1 `UPDATE vehicle_state ... WHERE recorded_at < ?` (guarda de monotonía)
- [ ] 4.2 Aplicar `MotionDetector` solo cuando el mensaje es más reciente que el último procesado

## 5. Presencia con LWT
- [ ] 5.1 Documentar el contrato de testamento que deben registrar los dispositivos al conectar
- [ ] 5.2 Consumidor de `fleet/+/vehicle/+/status` que actualiza el flag online
- [ ] 5.3 Simulador de dispositivo (utilidad de desarrollo) que emite telemetría y registra su testamento

## 6. Tests (Testcontainers con PostGIS y Mosquitto reales)
- [ ] 6.1 Idempotencia: el mismo mensaje procesado dos veces produce una sola fila
- [ ] 6.2 Desorden: un mensaje antiguo tras uno reciente NO retrocede `vehicle_state`
- [ ] 6.3 Desorden: el mensaje antiguo SÍ se persiste en `positions`
- [ ] 6.4 Ráfaga de reenvío: 1.000 posiciones acumuladas se insertan sin duplicados
- [ ] 6.5 Posición implausible descartada, no persistida
- [ ] 6.6 Desconexión abrupta del dispositivo → el broker publica el testamento → el vehículo queda offline
- [ ] 6.7 Reconexión → el vehículo vuelve a online
- [ ] 6.8 El plan de ejecución de la consulta de histórico por vehículo y rango NO contiene recorrido secuencial sobre `positions`
- [ ] 6.9 Payload malformado no derriba el consumidor
- [ ] 6.10 El apagado ordenado descarga el buffer pendiente

## Definición de terminado
- [ ] El simulador emite desde 50 vehículos durante 10 minutos sin crecimiento monótono de memoria del proceso
- [ ] Cortar el simulador de golpe marca esos vehículos como offline sin intervención
- [ ] Las particiones de la semana siguiente existen antes de que empiece esa semana
