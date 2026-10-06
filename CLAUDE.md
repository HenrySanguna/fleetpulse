# Fleetpulse

Real-time fleet tracking. Nx monorepo: Java Spring Boot 4.1 backend (Gradle, Kotlin DSL) + Angular 21 console. The binding constraints, stack, and prohibitions live in `openspec/project.md`; read it before proposing or implementing anything non-trivial.

## Stack overrides

The global NestJS and Ionic/Capacitor conventions do not apply here. The backend is Spring Boot, not NestJS; there is no mobile app. The global Angular conventions (standalone, OnPush, signals, `@if`/`@for`, typed forms) do apply to `apps/console`.

## Backend (`backend/`)

- Modules: `api` and `processor` depend on `domain` and `geo-core`, never on each other. `geo-core` is pure Java with no Spring, JPA, or I/O.
- Java 21. No `null` at public boundaries: use `Optional` or nullability annotations.
- Telemetry writes are batched (`JdbcTemplate.batchUpdate`); deduplication relies on a unique DB constraint.
- Spatial logic uses PostGIS `ST_*` in native queries, never Java-side geometry math on loaded rows.
- Schema changes go through Flyway migrations in `domain`.
- Run from `backend/`: `./gradlew test`, `./gradlew build`.

## Frontend

- `libs/api-client` is generated from the backend OpenAPI (`npm run generate:api-client`); never edit it by hand. A contract change regenerates the client in the same change.
- Tests: Vitest (unit), Playwright (`apps/console-e2e`).

## Checks

- `npx nx affected -t test` and `npx nx affected -t build` before closing a task.
- Integration tests use Testcontainers with real PostGIS and Mosquitto, not mocks.
