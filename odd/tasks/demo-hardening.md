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
- [ ] T5 Infra: Caddyfile timeouts/body limits, `mosquitto.prod.conf` limits on 9001, Tomcat/Hikari/statement timeout
- [ ] T6 Demo dispatcher seed SQL + README live demo section and security/limitations updates

## Route declaration
Delegated direct, one writer at a time, grouped: A = T1-T3, B = T4, C = T5-T6 (each touches 2+ non-trivial files and needs reading to prepare the write).

## Progress / evidence
- T1 `b4fc0e0`: RED = RateLimitFilterTest compile failure (classes missing). GREEN = RateLimitFilterTest (7 unit) + RateLimitWiringTest (login 429 + Retry-After through real chain) pass; full :api:test green (3m44s) before wiring test. Deviations: Bucket4j artifact is com.bucket4j:bucket4j_jdk17-core:8.21.0 (io.github.bucket4j:bucket4j-core does not exist; com.bucket4j:bucket4j-core stops at 8.10.1); 429 uses setStatus not sendError because the /error dispatch is authenticated and turned it into 401; src/test/resources/application.properties raises rate limits to 100000 so existing suites that log in repeatedly are not throttled.
- T2 `dcb3383`: RED = SessionCapTest assertion 'Expected size: 2 but was: 3'. GREEN = SessionCapTest (cap eviction oldest-first, other users untouched, 20m max inactive interval) passes. Eviction runs in the login success handler excluding the current session, keeping newest (cap-1) others.
- T3 `50cdf99`: RED = BrowserMqttCredentialCapTest compile failure (countByUserAndExpiresAtAfter and 3-arg properties missing). GREEN = BrowserMqttCredentialCapTest (429 before any broker call at cap, no save; issues below cap). New property fleetpulse.mqtt.browser.max-active-per-user default 50. Verification: `cd backend && ./gradlew build` = BUILD SUCCESSFUL (11m27s, all modules incl. full :api:test).
- T4 `6dde288`: RED = 9 of 31 new/changed endpoint tests failing (track default window, 24h cap, from>=to, row cap; alerts limit/default 200/limit range/type cap; activity-report bad range). GREEN = VehicleTrackEndpointTest, AlertsEndpointTest, ActivityReportEndpointTest, ActivityReportServiceClockGateTest pass; `./gradlew :api:test` BUILD SUCCESSFUL (3m33s); `npm run generate:api-client:check` in sync; `npx nx affected -t test` console 306 tests pass; `npx nx affected -t build` console OK. Properties: `fleetpulse.map.track.{default-window=2h,max-window=24h,max-rows=5000}`, `fleetpulse.reports.activity.max-range-days=31`; alerts limit default 200/max 500 and max 10 type filters are constants in AlertsController. Activity-report validation lives in ActivityReportController (service constructor unchanged). Existing fixed-date track tests now pass explicit from/to. Client regen only touched alertsController (`limit` param).

## Next step
Writer B done (T4); launch writer C (T5-T6).
