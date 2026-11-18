# demo-hardening

Mirror: Engram `odd/demo-hardening/tasks` (plan source: `odd/demo-hardening/plan`, #178).

## Objective
Harden FleetPulse so a shared public `DISPATCHER` demo account can be published in the README without allowing cheap DoS on the API, Mosquitto or Neon. The `FLEET_ADMIN` account stays private.

## Constraints
- Rate limiting lives in Spring (Bucket4j + Caffeine); prod Caddy is stock `caddy:2-alpine`.
- Branch `feat/demo-hardening`; one work-unit commit per task; no push.
- Contract changes regenerate `libs/api-client` in the same change.
- Prod Caddy/Mosquitto config is not validated by CI: validate locally.

## Tasks
- [x] T1 RateLimitFilter + `FleetpulseRateLimitProperties` (login 5/min/IP, mqtt credentials 6/min/IP, `/api/**` 120/min/IP, `/actuator/**` exempt, 429 + Retry-After) + tests
- [x] T2 Session timeout 20m + per-user session cap (default 25, evict oldest) + tests
- [x] T3 MQTT browser credential cap per user (default 50, reject 429 before touching broker) + tests
- [x] T4 Endpoint limits: `/track` window/row cap, `/activity-report` range, `/api/alerts` limit; regenerate api-client; tests
- [x] T5 Infra: Caddyfile timeouts/body limits, `mosquitto.prod.conf` limits on 9001, Tomcat/Hikari/statement timeout
- [x] T6 Demo dispatcher seed SQL + README live demo section and security/limitations updates

## Route declaration
Delegated direct, one writer at a time, grouped: A = T1-T3, B = T4, C = T5-T6 (each touches 2+ non-trivial files and needs reading to prepare the write).

## Progress / evidence
- T1 `b4fc0e0`: RED = RateLimitFilterTest compile failure (classes missing). GREEN = RateLimitFilterTest (7 unit) + RateLimitWiringTest (login 429 + Retry-After through real chain) pass; full :api:test green (3m44s) before wiring test. Deviations: Bucket4j artifact is com.bucket4j:bucket4j_jdk17-core:8.21.0 (io.github.bucket4j:bucket4j-core does not exist; com.bucket4j:bucket4j-core stops at 8.10.1); 429 uses setStatus not sendError because the /error dispatch is authenticated and turned it into 401; src/test/resources/application.properties raises rate limits to 100000 so existing suites that log in repeatedly are not throttled.
- T2 `dcb3383`: RED = SessionCapTest assertion 'Expected size: 2 but was: 3'. GREEN = SessionCapTest (cap eviction oldest-first, other users untouched, 20m max inactive interval) passes. Eviction runs in the login success handler excluding the current session, keeping newest (cap-1) others.
- T3 `50cdf99`: RED = BrowserMqttCredentialCapTest compile failure (countByUserAndExpiresAtAfter and 3-arg properties missing). GREEN = BrowserMqttCredentialCapTest (429 before any broker call at cap, no save; issues below cap). New property fleetpulse.mqtt.browser.max-active-per-user default 50. Verification: `cd backend && ./gradlew build` = BUILD SUCCESSFUL (11m27s, all modules incl. full :api:test).
- T4 `6dde288`: RED = 9 of 31 new/changed endpoint tests failing (track default window, 24h cap, from>=to, row cap; alerts limit/default 200/limit range/type cap; activity-report bad range). GREEN = VehicleTrackEndpointTest, AlertsEndpointTest, ActivityReportEndpointTest, ActivityReportServiceClockGateTest pass; `./gradlew :api:test` BUILD SUCCESSFUL (3m33s); `npm run generate:api-client:check` in sync; `npx nx affected -t test` console 306 tests pass; `npx nx affected -t build` console OK. Properties: `fleetpulse.map.track.{default-window=2h,max-window=24h,max-rows=5000}`, `fleetpulse.reports.activity.max-range-days=31`; alerts limit default 200/max 500 and max 10 type filters are constants in AlertsController. Activity-report validation lives in ActivityReportController (service constructor unchanged). Existing fixed-date track tests now pass explicit from/to. Client regen only touched alertsController (`limit` param).

- T5 `766db42`: Caddyfile validated with `caddy validate` in caddy:2-alpine ("Valid configuration"). mosquitto.prod.conf parsed by eclipse-mosquitto:2 (2.1.2) with dynsec path swapped to a temp file: starts and listens on 1883/9001 with no error or warning. `message_size_limit` is deprecated in 2.1.2 (startup note), so `max_packet_size 262144` is used instead, placed under `listener 9001`; `max_connections 200` is 9001 only. application.yml: Tomcat connection-timeout 10s, accept-count 50, max-swallow-size 256KB; Hikari connection-timeout 5000 and `socketTimeout=30` (pgjdbc 42.7.13 defines the property, seconds). `:api:test --tests '*RateLimitWiringTest'` and `:api:build -x test` BUILD SUCCESSFUL. Pending outside repo: add `options=-c statement_timeout=15000` to SPRING_DATASOURCE_URL on the VM (Neon pooled endpoints reject startup `options`; use the direct endpoint or `ALTER ROLE ... SET statement_timeout`).
- T6 `3677deb`: seed adds `demo@fleetpulse.dev` DISPATCHER (Argon2id hash generated offline with Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8; `matches()` true for the password, false for a wrong one). Compose already mounts the seed file; no change needed. SQL not executed against a database. README: Live demo section, Security model, local accounts note, limitations.

- Full check pass 2026-11-12 on `75babee`: `cd backend && ./gradlew build` = BUILD SUCCESSFUL (10m59s, 15 executed / 20 up-to-date). `npx nx affected -t test build --base=main` = console test + build OK (both cache hits; T5-T6 do not touch console inputs). First nx run failed with "Failed to process project graph" while the Gradle build ran concurrently; `nx show projects` then succeeded and the rerun passed.

## Next step
Re-run `demo-seed` on the VM, set statement_timeout (direct Neon endpoint `options` or `ALTER ROLE ... SET statement_timeout`), then decide on push/PR (work-unit split for delivery).
