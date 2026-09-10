# Tasks: Add Fleet Auth

## Review Workload Forecast

**Decision needed before apply: Yes**
**Chained PRs recommended: Yes**
**400-line budget risk: High**
**Chain strategy: pending — awaiting orchestrator/user decision**

This forecast was produced by `sdd-apply` on first launch, not by `sdd-tasks` (this change's `tasks.md` predates the Review Workload Forecast convention). No code was written before this forecast; `sdd-apply` stopped per the mandatory workload guard.

### Why this change is high risk for the 400-line budget

- 25 tasks across 6 sections, all security-sensitive: JPA entities + Flyway migration, Spring Security dispatcher session, ephemeral browser MQTT credentials, device credentials with revocation/rotation, Mosquitto auth/ACL backend integration, and 7 Testcontainers security-isolation tests against a real Mosquitto broker.
- `Vehicle` does not exist yet anywhere in the codebase (checked: no matches in any archived or pending change except this one and `04-add-live-map`, which only consumes `/api/vehicles/{id}/track`). Task 1.2 ("Device → Vehicle → Organization") means this change must also introduce a minimal `Vehicle` entity, which is additional scope beyond what the task text alone suggests.
- Mosquitto ships as the stock `eclipse-mosquitto:2` image (see `docker-compose.yml`, `docker/mosquitto/mosquitto.conf`) with no custom auth plugin built in. Making the broker consult our own credential store (task 5.1) realistically means either (a) building and shipping a custom Mosquitto auth plugin image (large, non-trivial OSI-licensed dependency addition), or (b) driving the broker's built-in `dynamic-security` plugin (ships in the stock image, EPL/EDL, zero new dependency) from the backend via its `$CONTROL/dynamic-security/v1` control topic. Option (b) is the realistic zero-cost choice but is still a non-trivial MQTT admin client plus a documented deviation from design.md's literal wording ("Mosquitto valida credenciales... contra el almacén gestionado por el backend") since the plugin keeps its own JSON-backed store that the backend pushes into, rather than Mosquitto querying Postgres directly.
- Force-disconnecting an active device session on revocation (task 4.2, spec scenario "Revocación de un dispositivo conectado") requires the same admin-client plumbing plus a Testcontainers test that actually holds an open MQTT connection, revokes it out-of-band, and asserts the connection drops — inherently more code than a unit test.

### Rough line-count estimate (additions + deletions, authored code)

| Section | Scope | Estimate |
|---|---|---|
| 1. Modelo | `Organization`, `User`, `Vehicle`, `Device`, `MqttCredential` entities + repositories + enums, Flyway `V2` migration (business tables + Spring Session JDBC schema), Spring Session JDBC wiring, persistence/migration tests | ~650–750 |
| 2. Sesión de despachador | Spring Security config (form login, password encoder, `HttpOnly`/`Secure`/`SameSite=Strict` cookie), `UserDetailsService`, per-request `orgId` resolution, method-security role checks, session invalidation on deactivate (incl. spec scenario / task 6.7), tests | ~550–650 |
| 3 + 5. Browser MQTT credentials + Mosquitto integration backbone | Mosquitto admin client (dynamic-security control-topic driver), both-listener anonymous-deny config, `GET /api/mqtt/credentials`, read-only org-scoped ACL registration, scheduled purge, cross-org isolation test (6.1 — "most important test of this change" per design.md), expired-credential test (6.4), anonymous-rejected test (6.6) | ~750–850 |
| 4. Device credentials | Device provisioning + vehicle-scoped ACL, revocation with forced disconnect, rotation, tests (6.2, 6.3, 6.5) | ~700–800 |

**Total estimate: ~2,650–3,050 changed lines.** Even the smallest single section (~550–650) is well above the 400-line budget on its own; this is not a borderline case.

### Proposed chained-PR work units (feature-branch-chain candidate)

If `feature-branch-chain` is selected, PR #1 targets `feat/fleet-auth`, each later PR targets the immediately previous PR's branch, and the tracker branch aggregates to `main` once all slices land:

1. **WU1 — Data model & migration** (tasks 1.1–1.3): entities, `Vehicle` addition, Flyway migration, Spring Session JDBC. Self-contained, testable without Mosquitto. ~650–750 lines — still needs `size:exception` even alone, or further split (e.g. entities+migration vs. Spring Session JDBC).
2. **WU2 — Dispatcher session & authorization** (tasks 2.1–2.5 + spec scenario 6.7): builds on WU1. No Mosquitto dependency. ~550–650 lines.
3. **WU3 — Mosquitto backbone + browser ephemeral credentials** (tasks 5.1–5.2, 3.1–3.3 + scenarios 6.1, 6.4, 6.6): builds on WU1+WU2. Carries the flagship cross-org isolation test. ~750–850 lines.
4. **WU4 — Device credentials** (tasks 4.1–4.3 + scenarios 6.2, 6.3, 6.5): builds on WU1 and WU3 (reuses the Mosquitto admin client). ~700–800 lines.

Each of these 4 work units still exceeds 400 lines on its own; a finer split (e.g. separating entities from Spring Session JDBC in WU1, or separating revoke from rotate in WU4) could approach 7–9 PRs closer to budget, at the cost of PRs that are not independently a complete vertical slice for TDD purposes (RED/GREEN pairs for one capability would span two PRs).

### Options for the orchestrator/user to resolve before `sdd-apply` proceeds

- **(a)** Accept `size:exception` per work unit above (4 PRs, each ~550–850 lines, each a cohesive tested vertical slice).
- **(b)** Request a finer chained/stacked split closer to the 400-line budget (more PRs, narrower diffs).
- **(c)** Accept a single PR with an explicit `size:exception` covering the full ~2,650–3,050 lines (matches this repo's precedent in changes `00` and `01`, which shipped as single large PRs, but conflicts with this change's explicitly elevated-risk delivery strategy).

No implementation code has been written. `sdd-apply` will resume from task 1.1 once a chain strategy (or `size:exception`) is confirmed.

## 1. Modelo
- [ ] 1.1 Entidades JPA `Organization`, `User`, `Device`, `MqttCredential`; migración Flyway
- [ ] 1.2 Relación `Device` → `Vehicle` → `Organization`
- [ ] 1.3 Spring Session con JDBC para persistir sesiones de despachador

## 2. Sesión de despachador
- [ ] 2.1 Spring Security con autenticación por formulario y hash de contraseña con Argon2 o BCrypt
- [ ] 2.2 Cookie de sesión `HttpOnly`, `Secure`, `SameSite=Strict`
- [ ] 2.3 Resolución del `orgId` del usuario autenticado en cada petición
- [ ] 2.4 Autorización por rol (`DISPATCHER`, `FLEET_ADMIN`) con anotaciones de método
- [ ] 2.5 Invalidación de sesión al desactivar un usuario

## 3. Credenciales MQTT de navegador
- [ ] 3.1 `GET /api/mqtt/credentials`: genera credenciales efímeras ligadas a la sesión
- [ ] 3.2 Registro de ACL de solo lectura sobre `fleet/{orgId}/#`
- [ ] 3.3 Tarea `@Scheduled` que purga credenciales expiradas

## 4. Credenciales de dispositivo
- [ ] 4.1 Alta de dispositivo con ACL acotada a su propio vehículo
- [ ] 4.2 Revocación: elimina credencial y fuerza desconexión de la sesión activa en el broker
- [ ] 4.3 Rotación de credencial sin dar de baja el dispositivo

## 5. Integración con Mosquitto
- [ ] 5.1 Backend de autenticación y ACL de Mosquitto apoyado en el almacén de credenciales
- [ ] 5.2 Configuración que prohíbe conexiones anónimas en **ambos** listeners (TCP y WebSocket)

## 6. Tests (Testcontainers con Mosquitto real)
- [ ] 6.1 Un despachador de la organización A NO puede suscribirse a tópicos de la organización B
- [ ] 6.2 Un dispositivo NO puede publicar en el tópico de telemetría de otro vehículo
- [ ] 6.3 Un dispositivo NO puede suscribirse al tópico de alertas de su organización
- [ ] 6.4 Credenciales de navegador expiradas no permiten conexión
- [ ] 6.5 Revocar un dispositivo corta su conexión activa
- [ ] 6.6 Conexión anónima rechazada en ambos listeners
- [ ] 6.7 Desactivar un despachador invalida su sesión HTTP en la petición siguiente

## Definición de terminado
- [ ] Ningún test consigue una suscripción cruzada entre organizaciones
- [ ] El broker rechaza conexiones anónimas también en el listener de WebSocket
