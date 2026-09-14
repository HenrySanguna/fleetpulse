# Tasks: Add Live Map

## Review Workload Forecast

**Decision needed before apply: No — resolved automatically 2026-09-14 (delivery_strategy=auto-chain)**
**Chained PRs recommended: Yes**
**400-line budget risk: High**
**Chain strategy: feature-branch-chain — tracker `feat/live-map`, each work-unit branch off the immediately previous one**

This forecast was produced by `sdd-apply` on first launch, not by `sdd-tasks` (this change's `tasks.md` predates the Review Workload Forecast convention, same precedent as `02-add-fleet-auth` and `03-add-telemetry-ingest`). No code was written before this forecast. Per the session's `delivery_strategy=auto-chain`, the split below was applied automatically without stopping to ask — same authority `03-add-telemetry-ingest` used after its maintainer resolution, applied here directly since auto-chain already resolves the decision.

### Why this change is high risk for the 400-line budget

- 29 tasks across 6 sections plus 2 "Definición de terminado" items, spanning a browser MQTT/WebSocket client with credential renewal and exponential backoff, one new backend endpoint, a snapshot+buffer+replay startup sequence with a monotonicity guard, an NgRx SignalStore, a MapLibre GL map with `requestAnimationFrame` interpolation, a full console UI (side panel, filters, detail, connection indicator), and Playwright E2E coverage.
- `apps/console` is currently the bare Nx scaffold (`app.ts`, `app.routes.ts`, `app.config.ts` with only `provideZonelessChangeDetection`/`provideRouter`, `nx-welcome.ts`) — nothing under `core/`, `shared/`, or `features/` exists yet, and `libs/console-ui` (named in the proposal) does not exist at all. Every task pays greenfield setup cost, not incremental cost.
- None of `mqtt` (MQTT.js), `maplibre-gl`, `@ngrx/signals`, `primeng`, or `tailwindcss-primeui` are in `package.json` yet — each is a genuinely new dependency plus its own wiring (HttpClient/api-client provider, Tailwind v4 CSS-based config, PrimeNG theme preset), none of which is free.
- **Gap discovered, not in the original task list**: design.md and task 4.6 assume the backend already serves a simplified historical track over HTTP (`GET /api/vehicles/{id}/track`, matching `httpResource` in design.md), and `Geo.simplifyTrack` already exists idle in `geo-core` since change 03 (`GeoSimplifyTrackTest` proves it). No task in this file actually creates that HTTP endpoint — only `GET /api/fleet/state` (2.1) is listed. Resolved by folding the track endpoint into task 2.1's scope (WU2) rather than leaving 3.3/4.6 unimplementable; documented here per the apply skill's "note deviations, don't silently freelance" rule.
- Section 4 (map) is inherently a single cohesive vertical slice — MapLibre setup, the symbol layer, heading/state/offline styling, `requestAnimationFrame` interpolation, and the stop-without-extrapolating rule are one rendering pipeline that resists further splitting without breaking cohesion, matching `03-add-telemetry-ingest`'s WU4 precedent (implausibility+batch-writer) for "still above budget as one honest slice."
- Section 5 (console UI) both creates `libs/console-ui` from scratch and adds PrimeNG + Tailwind wiring that no other section needs, so it cannot be folded into an earlier slice without forcing unrelated dependencies on it.

### Rough line-count estimate (additions + deletions, authored code; lockfile/generated api-client diffs excluded from the authored-risk count, consistent with `sdd-phase-common.md`'s generated-goldens exclusion)

| Section | Scope | Estimate |
|---|---|---|
| 1. Cliente MQTT en el navegador | `MqttConnectionService` (MQTT.js over WS), credential fetch + proactive renewal, manual exponential-backoff reconnect (MQTT.js's own `reconnectPeriod` is fixed-interval, not exponential), topic subscription, connection-status signal, message stream, `app.config.ts` HTTP/api-client wiring, unit tests | ~380–560 |
| 2. Backend: snapshot + track endpoints | `GET /api/fleet/state` (thin controller + service + response DTO, querying `Vehicle`/`vehicle_state`), `GET /api/vehicles/{id}/track` (gap above), Testcontainers tests, regenerated `libs/api-client` (excluded from count) | ~350–520 |
| 3. Arranque snapshot + stream / estado cliente | Subscribe-before-snapshot sequence, buffer-and-replay with monotonicity guard, full-cycle repeat on reconnect, `FleetStore` (SignalStore) with `Map<vehicleId, VehicleState>`, `visibleVehicles` filtered selector, unit tests 6.1/6.2 | ~420–620 |
| 4. Mapa | `httpResource` track fetch, MapLibre GL integration (free tile provider), vehicle symbol layer, heading/motion-state/offline styling, `requestAnimationFrame` interpolation with stop-without-extrapolate, simplified historical track line layer, unit tests 6.3/6.4 | ~480–700 |
| 5. Consola | New `libs/console-ui` (PrimeNG + Tailwind v4 wiring), side panel (list/filters/search), list↔map selection sync, vehicle detail (reported data only), MQTT connection-status indicator | ~420–620 |
| 6. E2E + Definición de terminado | Playwright 6.5 (published MQTT message moves the marker) and 6.6 (disconnect notice + resync), 50-vehicle simulator soak evidence, API-down-doesn't-freeze-the-map evidence | ~250–450 |

**Total estimate: ~2,300–3,470 changed lines.** Every section individually approaches or exceeds the 400-line budget; this is not a borderline case, matching `03-add-telemetry-ingest`'s precedent for a frontend+backend greenfield vertical.

### Finalized work units (feature-branch-chain)

PR #1 targets `feat/live-map` (the tracker branch, created off `main`); each later PR targets the immediately previous PR's branch; the tracker branch aggregates to `main` once all slices land.

| Work unit | Task IDs covered | Line estimate | Branch | Builds on |
|---|---|---|---|---|
| WU1 — Cliente MQTT en el navegador | 1.1, 1.2, 1.3, 1.4 | ~380–560 | `feat/live-map-wu1-mqtt-client` | Tracker only. Self-contained transport service (credentials → connect → renew → backoff-reconnect → subscribe); testable with a mocked MQTT.js client, no map/state dependency yet. |
| WU2 — Backend: snapshot + track endpoints | 2.1 (extended per the gap above) | ~350–520 | `feat/live-map-wu2-fleet-state-endpoint` | WU1 (branch lineage only). Logically independent: pure backend controller/service/DTO work against already-existing `Vehicle`/`vehicle_state`/`positions` tables and the already-existing `Geo.simplifyTrack`. |
| WU3 — Arranque snapshot+stream / estado cliente | 2.2, 2.3, 2.4, 3.1, 3.2 + tests 6.1, 6.2 | ~420–620 | `feat/live-map-wu3-client-state` | WU1 (MQTT transport) + WU2 (snapshot HTTP shape). The core coupling design.md calls out explicitly: subscribe-before-snapshot only makes sense once both sides exist. |
| WU4 — Mapa | 3.3, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6 + tests 6.3, 6.4 | ~480–700 | `feat/live-map-wu4-map-rendering` | WU3 — renders from `FleetStore`'s state and needs the track `httpResource` alongside the map that draws it. |
| WU5 — Consola | 5.1, 5.2, 5.3, 5.4 | ~420–620 | `feat/live-map-wu5-console-ui` | WU4 — the side panel's selection sync and detail view both read the map/store surface WU4 established. |
| WU6 — E2E + Definición de terminado | 6.5, 6.6 + both DoD items | ~250–450 | `feat/live-map-wu6-e2e-and-dod` | WU5 — end-to-end scenarios need the full console (map + panel + indicator) running together. |

**WU1 (Cliente MQTT en el navegador) is done** — see the resolution notes next to tasks 1.1–1.4 below. Final authored diff: 452 lines across 5 files (`app.config.ts`, `core/mqtt/mqtt-connection.service.ts`, `core/mqtt/mqtt-connection.models.ts`, `core/mqtt/mqtt-connection.service.spec.ts`, this file), within the ~380–560 estimate; `package.json`/`package-lock.json` (`mqtt` dependency addition) excluded from the authored count per convention. `sdd-apply` resumes at WU2 (backend snapshot + track endpoints).

## 1. Cliente MQTT en el navegador
- [x] 1.1 Servicio de conexión MQTT (MQTT.js) sobre WebSocket con credenciales de `GET /api/mqtt/credentials` — **WU1**: `MqttConnectionService` (`apps/console/src/app/core/mqtt/mqtt-connection.service.ts`), fetches credentials via the generated `MqttCredentialsControllerService`, then `mqtt.connect(wsUrl, { username, password, clean: true, reconnectPeriod: 0 })`. `app.config.ts` now wires `provideHttpClient(withFetch())` + `provideApi({ withCredentials: true })` (session-cookie + CSRF auth, matching `SecurityConfig`'s `csrf().spa()`) — neither existed before this change; `apps/console` was the bare Nx scaffold.
- [x] 1.2 Renovación de credenciales antes de expirar — `scheduleCredentialRenewal` computes `expiresAt - 30s` (floor 1s) and re-runs the full credential-fetch-and-connect cycle, ending the stale client first.
- [x] 1.3 Reconexión con backoff exponencial — MQTT.js's own `reconnectPeriod` is fixed-interval, not exponential, so it is disabled (`reconnectPeriod: 0`); `scheduleReconnect` implements `min(1000 * 2^attempt, 30000)` manually on unexpected `close`, resetting on a successful `connect`.
- [x] 1.4 Suscripción a `fleet/{orgId}/vehicle/+/telemetry` y `.../status` — `subscribeToVehicleTopics`, called on every successful `connect` event.

## 2. Arranque snapshot + stream
- [ ] 2.1 `GET /api/fleet/state` en `api`: estado actual de todos los vehículos de la organización
- [ ] 2.2 Secuencia de arranque: suscribir → bufferizar → snapshot → aplicar buffer → directo
- [ ] 2.3 Guarda de monotonía al aplicar el buffer (descartar lo anterior al snapshot)
- [ ] 2.4 Repetir el ciclo completo al reconectar

## 3. Estado en el cliente
- [ ] 3.1 SignalStore con `Map<vehicleId, VehicleState>` alimentado por MQTT
- [ ] 3.2 Selector computado de vehículos visibles según filtros (estado, online, búsqueda)
- [ ] 3.3 `httpResource` para la traza histórica, consumiendo `libs/api-client`

## 4. Mapa
- [ ] 4.1 Integrar MapLibre GL con teselas de proveedor gratuito
- [ ] 4.2 Capa de símbolos para los vehículos (no marcadores DOM)
- [ ] 4.3 Icono orientado por rumbo; color por estado de movimiento; atenuado si está offline
- [ ] 4.4 Interpolación visual entre posiciones con `requestAnimationFrame`
- [ ] 4.5 Detener la interpolación (sin extrapolar) si no llega posición en la ventana esperada
- [ ] 4.6 Dibujar la traza histórica que el backend ya devuelve simplificada

## 5. Consola
- [ ] 5.1 Panel lateral con lista de vehículos, filtros y búsqueda (PrimeNG), envuelto en `libs/console-ui`
- [ ] 5.2 Selección sincronizada entre lista y mapa
- [ ] 5.3 Detalle de vehículo con datos reales (nunca interpolados)
- [ ] 5.4 Indicador de estado de conexión MQTT en la interfaz

## 6. Tests
- [ ] 6.1 Unitario: aplicar un mensaje más antiguo que el snapshot no modifica el estado
- [ ] 6.2 Unitario: los mensajes bufferizados durante la carga se aplican tras el snapshot
- [ ] 6.3 Unitario: el detalle del vehículo muestra la posición reportada, no la interpolada
- [ ] 6.4 Unitario: sin mensajes durante N ventanas, la interpolación se detiene
- [ ] 6.5 E2E (Playwright): un mensaje MQTT publicado por el test mueve el marcador en pantalla
- [ ] 6.6 E2E: al perder la conexión, la interfaz muestra el aviso; al recuperarla, el estado se resincroniza

## Definición de terminado
- [ ] Con el simulador de 50 vehículos corriendo, el mapa se mantiene fluido y la memoria del navegador no crece de forma monótona durante 10 minutos
- [ ] Detener el proceso `api` con el mapa abierto NO congela las actualizaciones en vivo (siguen llegando por MQTT)
