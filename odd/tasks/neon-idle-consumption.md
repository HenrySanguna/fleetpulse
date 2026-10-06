# Neon idle consumption (let Neon compute scale to zero)

## Objective
Reduce Neon compute-hour consumption by removing periodic DB activity that keeps the compute awake while the system is idle, without changing any observable Fleetpulse behavior.

## Problem / evidence
- 2026-09-29 deploy (run 36539902425, fcb1e43) failed: Neon returned "Your account or project has exceeded the quota".
- Neon suspends compute only after ~5 min without activity. Current periodic DB touches (verified in code):
  - api Docker HEALTHCHECK every 10s hits `/actuator/health`, which runs the default DataSource health indicator (`Dockerfile:49`, `docker/backend/healthcheck.sh:24`).
  - Spring Session JDBC expired-session cleanup, default cron every minute (`spring.session.store-type: jdbc`, no cleanup-cron override).
  - HikariCP defaults: `minimumIdle == maximumPoolSize` (10) per app; `maxLifetime` 30 min recycles idle connections, reconnecting to Neon on its own.
  - processor `TripSegmentationTask` (5 min) and `VehicleRollupTask` (15 min) query the DB even with no telemetry.

## Constraint (user, 2026-09-29)
Only changes that reduce Neon consumption AND do not affect how Fleetpulse works.

## Excluded (would change behavior)
- `ExpiredMqttCredentialPurgeTask` interval: it also revokes the broker-side dynsec client, so a longer interval lets expired browser credentials connect longer (spec scenario 6.4).
- Starting the demo simulator on every deploy (`ci.yml` deploy step): changing it changes demo behavior after deploy.
- Neon `-pooler` URL: lives in the VM's untracked `.env`; user action, not a repo change.

## TDD
Off (project/session convention, see `qa-followups.md`). Checks: `backend/gradlew.bat :api:check`, `backend/gradlew.bat :processor:check` (Docker running for Testcontainers).

## Tasks
- [x] T1 api Docker healthcheck uses a health group that excludes only the `db` indicator; full `/actuator/health` (used by CI `verify-deploy` and humans) unchanged. (d7d2780; `/actuator/health/container` added to SecurityConfig permitAll; `validate-group-membership: false` for no-DataSource contexts; only mosquitto is a `service_healthy` dependency)
- [x] T2 Spring Session JDBC cleanup cron from every minute to hourly (expired sessions are already rejected on read). (d4f97f9; default `0 * * * * *` confirmed in spring-boot-session-jdbc 4.1.1; `findById` deletes expired sessions on read)
- [x] T3 HikariCP `minimum-idle: 0` plus an idle timeout in api and processor; max pool size unchanged. (8703008; idle-timeout 120000)
- [x] T4 processor trip segmentation and rollup tasks skip a run only when it is provably a no-op (no telemetry since the last run and the previous run left nothing pending); always run after startup. (4401a96; in-memory `TelemetryActivity`; trips stop ~10 min after last telemetry; rollups run hourly while data is inside the sliding window, ~24 h after last telemetry; only `updated_at` refresh differs on skipped runs, unread)

- [x] T5 api `ExpiredMqttCredentialPurgeTask` skips its DB query while no browser credential can have expired (in-memory next-expiry, refreshed on issuance and after each purge); still queries on startup; revocation latency unchanged (<= 60 s after expiry). Branch `fix/mqtt-purge-idle` from origin/main (6828150). Evidence (2026-10-01): task queries `findByExpiresAtBefore` every 60 s, below Neon's ~5 min suspend threshold, after #92 deploy. (6422c4c; `MqttCredentialExpiryTracker` synchronized known/earliest/issuedSincePurgeStart; issuance notifies after non-transactional save (commit precedes notify); purge: canSkip -> startPurge -> purge -> finishPurge(min(findEarliestExpiresAt, issuedSincePurgeStart)); throw leaves state unknown)

## Routes
- T1-T4: one delegated backend writer (2+ non-trivial files; trigger: writer + preparation). Sequential work-unit commits.
- T5: one delegated backend writer (tracker + purge task + issuance service + repository + tests; trigger: writer 2+ non-trivial files).

## Delivery
Strategy: ask-on-risk. Forecast well under 400 authored lines; single PR.

## Progress / evidence
- Writer (delegated, sonnet): `:api:check` BUILD SUCCESSFUL after T3; `:processor:check` BUILD SUCCESSFUL, 178 tests, 0 failures after T4. No AI attribution in commit messages (verified by parent: 0 hits).
- Branch diff vs origin/main: 21 files, +854/-11 (mostly tests).
- RDD: on (default). Assess: high (`SecurityConfig.java` security hot path, `healthcheck.sh` shell). Preflight STATUS asks for an intended-untracked selection (untracked `CLAUDE.md`, `odd/`); submission refused as `invalid_request`. Review not started.
- Residual risk: manual DB edits of positions/vehicles/trip threshold while idle are not noticed until the next telemetry batch.

- Parent spot check: `backend/gradlew.bat :api:check` BUILD SUCCESSFUL (3m 28s).

- User chose push + PR without native review. PR #92 (fix/neon-idle-consumption -> main); body verified free of AI attribution.
- Correction: Neon `-pooler` URL is already in use (demo-seed reuses SPRING_DATASOURCE_URL and connected to the `-pooler` host); no VM change needed.

## Next step (T1-T4)
User merges #92 once Neon quota resets; then confirm deploy + verify-deploy green and Neon compute suspends when idle.

## Post-deploy (2026-10-01)
- #92 merged (6828150); CI run 36837279326 all green; prod /actuator/info SHA matches; /actuator/health UP; /actuator/health/container excludes db.
- Remaining waker found: `ExpiredMqttCredentialPurgeTask` (60 s DB query) -> T5.
- Docker Desktop down at T5 start; available again before the writer's checks ran.
- T5 writer (delegated, sonnet): `:api:check` BUILD SUCCESSFUL (114 tests, 0 failures, incl. `ExpiredBrowserCredentialConnectionTest` scenario 6.4). +327/-2, 6 files.
- Parent spot check: `:domain:check` 48/0 failures; `:api:test` purge/credential/6.4 tests 16/0 failures (--rerun-tasks).
- RDD assess (base origin/main, committed-only, untracked excluded): medium (executable_change), 329 lines, review_due=false (under_budget) -> pending in slice.
- PR #19 (archive 00-bootstrap-monorepo): base feat/geo-core (already in main); its files identical on main -> recommend close without merge.

- User authorized push + PR + merge. PR #93 (body free of AI attribution) checks green (affected, docker-and-compose-smoke); auto-merged as 33b2a1f. Main CI run 36847277885 all green incl. deploy-oracle + verify-deploy; prod /actuator/info SHA 33b2a1f, health UP. Native review not run (under_budget).

## Next step
User confirms in Neon (Branch -> Computes / Monitoring) that compute goes Idle ~5 min after last use. Close PR #19 without merging (user decision).
