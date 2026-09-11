```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:3c07d9346a834eb86f0630ea73dda099f7f7914024f38b15bdd89011ed383b6f
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 5/5
scenarios: 8/8
test_command: cd backend && ./gradlew.bat :api:test :domain:test :geo-core:test :processor:test verifyBackendDependencyDirection --continue --console=plain
test_exit_code: 0
test_output_hash: sha256:d1ceb9d51b6cfbd9a5d78609022185b4184aec81f62929a0d1aff08c3a631fe6
build_command: cd backend && ./gradlew.bat :api:test :domain:test :geo-core:test :processor:test verifyBackendDependencyDirection --continue --console=plain
build_exit_code: 0
build_output_hash: sha256:d1ceb9d51b6cfbd9a5d78609022185b4184aec81f62929a0d1aff08c3a631fe6
```

## Verification Report

**Change**: 02-add-fleet-auth
**Version**: N/A (single spec revision, no versioned iterations)
**Mode**: Strict TDD

**Evaluated state**: branch `feat/fleet-auth-wu4-device-creds`, HEAD commit `3b4bacee97884a5db3622a10c39788d35e87936f` -- this is a re-verify of the prior FAIL verdict. The only change since the prior verify run (evaluated at commit `6b32409`) is commit `3b4bace` ("test(auth): cover MQTT credential renewal after dispatcher deactivation"), which touches exactly 2 files: a new test class and a `tasks.md` edit adding task 6.8. No production code changed. `main` still does not contain this change (4 stacked, unmerged PRs #20-23 on tracker `feat/fleet-auth`).

### Completeness
| Metric | Value |
|--------|-------|
| Tasks total | 26 (24 numbered work tasks + task 6.8 added during verify remediation + 2 "Definicion de terminado" items) |
| Tasks complete | 26 |
| Tasks incomplete | 0 |

All 26 checkboxes in `tasks.md` are `[x]`. Task 6.8 is annotated in-line as "added during sdd-verify remediation," consistent with the apply-progress record (Engram `sdd/02-add-fleet-auth/apply-progress`, observation #87) and with commit `3b4bace`'s diff (exactly the new test file plus this one line).

### Build & Tests Execution
**Build**: Passed (compilation is implicit in the same Gradle multi-module invocation; no separate build step exists in this project beyond `compileJava`/`compileTestJava`, both of which ran and succeeded in every command below)

**Tests**: 105 passed / 0 failed / 0 skipped in the final clean run (up from the prior report's 104 -- the new `MqttCredentialsAfterDispatcherDeactivationTest` is additive with zero regressions)

Exact commands run, in order, against `feat/fleet-auth-wu4-device-creds` (working branch, no code changes made by this verify run):

```text
# Step 0: Docker Desktop was not running at session start (Testcontainers
# requires it); started it and waited for the daemon to become ready before
# any test command below.

# Step 1: new remediation test, isolated, forced fresh
cd backend && ./gradlew.bat :api:test --tests "dev.fleetpulse.api.mqtt.credentials.MqttCredentialsAfterDispatcherDeactivationTest" --console=plain --rerun-tasks
  -> BUILD SUCCESSFUL in 34s, exit 0
  -> 1/1 passed (JUnit XML: tests=1 failures=0 errors=0,
     testcase name=rejectsMqttCredentialRenewalOnTheNextRequestAfterDeactivation)

# Step 2: full aggregate suite, attempt 1
cd backend && ./gradlew.bat :api:test :domain:test :geo-core:test :processor:test verifyBackendDependencyDirection --continue --console=plain
  -> BUILD FAILED in 3m 10s, exit 1
  -> api:test: 47/47 PASSED (fresh)
  -> domain:test: 9/9 PASSED, geo-core:test: 37/37 PASSED (cached UP-TO-DATE, inputs unchanged)
  -> processor:test: 12 tests completed, 1 failed
     ProcessorHeartbeatPublisherTest publishesARetainedHeartbeatWithAFreshTimestamp FAILED
       java.util.concurrent.TimeoutException at ProcessorHeartbeatPublisherTest.java:116
  -> verifyBackendDependencyDirection not reached in the failure summary; confirmed separately (step 4)

# Isolated re-run of the exact failing test
cd backend && ./gradlew.bat :processor:test --tests "*ProcessorHeartbeatPublisherTest*" --console=plain
  -> BUILD SUCCESSFUL in 9s, exit 0 (confirms flakiness, not a regression)

# Step 3: full aggregate suite, attempt 2
cd backend && ./gradlew.bat :api:test :domain:test :geo-core:test :processor:test verifyBackendDependencyDirection --continue --console=plain
  -> BUILD FAILED in 3m 13s, exit 1
  -> Same single failure, same test, same line: ProcessorHeartbeatPublisherTest publishesARetainedHeartbeatWithAFreshTimestamp (second occurrence)
  -> api/domain/geo-core all green (47/9/37)

# Step 4: verifyBackendDependencyDirection run standalone to confirm it passes independent of the processor:test flake
cd backend && ./gradlew.bat verifyBackendDependencyDirection --console=plain
  -> BUILD SUCCESSFUL in 1s, exit 0

# Step 5: full aggregate suite, attempt 3 (final, clean)
cd backend && ./gradlew.bat :api:test :domain:test :geo-core:test :processor:test verifyBackendDependencyDirection --continue --console=plain
  -> BUILD SUCCESSFUL in 2m 37s, exit 0
  -> api:test 47/47, domain:test 9/9, geo-core:test 37/37, processor:test 12/12 (flaky test passed this time), verifyBackendDependencyDirection PASSED
```

JUnit XML reports (`backend/{api,domain,geo-core,processor}/build/test-results/test/TEST-*.xml`) from this final clean state independently confirm, summed per module: api 47, domain 9, geo-core 37, processor 12 = 105 tests total, 0 failures, 0 errors across every report file. sha256 of the concatenated XML report bytes from this run: d1ceb9d51b6cfbd9a5d78609022185b4184aec81f62929a0d1aff08c3a631fe6.

**Flaky-test finding, re-confirmed (WARNING, not a regression)**: `ProcessorHeartbeatPublisherTest.publishesARetainedHeartbeatWithAFreshTimestamp` failed in 2 of 3 full-suite attempts in this run (same symptom as the prior verify run: `TimeoutException` against a real MQTT round-trip through a Testcontainers Mosquitto broker), and passed cleanly every time it was re-run in isolation. This is the same pre-existing test the prior verify report flagged; it is unrelated to this change's file set and not touched by commit `3b4bace`. `ProcessorHeartbeatHealthIndicatorTest.reportsDownWhenTheRetainedHeartbeatIsStale` (the other test named in the prior report's WARNING) did not flake in this run's 3 attempts, consistent with it being non-deterministic host-load-dependent behavior rather than a fixed defect.

**Coverage**: Not measured for the security-relevant modules (Jacoco is configured for `geo-core` only; no coverage tool is wired for `api`/`domain`/`processor`) -> Not available

### Spec Compliance Matrix
| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| Aislamiento de datos entre organizaciones en el broker | Intento de suscripcion cruzada entre organizaciones | CrossOrganizationMqttIsolationTest > aDispatcherCannotSubscribeToAnotherOrganizationsTopicsButCanSubscribeToItsOwn | COMPLIANT |
| Aislamiento de datos entre organizaciones en el broker | Suscripcion legitima a la propia organizacion | CrossOrganizationMqttIsolationTest > aDispatcherCannotSubscribeToAnotherOrganizationsTopicsButCanSubscribeToItsOwn (same test, second assertion) | COMPLIANT |
| Confinamiento de credenciales de dispositivo a su propio vehiculo | Dispositivo intenta suplantar a otro vehiculo | DeviceTelemetryVehicleIsolationTest > aDeviceCanPublishToItsOwnVehicleButNotToAnothersVehicleTelemetry | COMPLIANT |
| Confinamiento de credenciales de dispositivo a su propio vehiculo | Dispositivo intenta escuchar topicos de alertas | DeviceAlertsSubscriptionRejectionTest > aDeviceCannotSubscribeToItsOrganizationsAlertsTopic | COMPLIANT |
| Expiracion de credenciales efimeras de navegador | Reconexion con credenciales caducadas | ExpiredBrowserCredentialConnectionTest > expiredBrowserCredentialsCannotConnectAfterThePurgeTaskRuns | COMPLIANT |
| Expiracion de credenciales efimeras de navegador | Renovacion tras revocar la sesion del despachador | MqttCredentialsAfterDispatcherDeactivationTest > rejectsMqttCredentialRenewalOnTheNextRequestAfterDeactivation | COMPLIANT (was UNTESTED / CRITICAL in the prior verify run) |
| Revocacion inmediata de dispositivos | Revocacion de un dispositivo conectado | DeviceRevocationForcedDisconnectTest > revokingAConnectedDeviceCutsItsActiveSessionAndBlocksReconnection | COMPLIANT |
| Prohibicion de acceso anonimo al broker | Conexion anonima por el listener WebSocket | MosquittoAnonymousConnectionRejectionTest > rejectsAnAnonymousConnectionOnTheWebSocketListener | COMPLIANT |

**Compliance summary**: 8/8 scenarios compliant.

**On the previously UNTESTED scenario**: independently read `MqttCredentialsAfterDispatcherDeactivationTest.java` (117 lines, package `dev.fleetpulse.api.mqtt.credentials`) in full. It creates a real organization and dispatcher user via JPA repositories against a Testcontainers PostGIS instance, logs in through `DispatcherLoginTestSupport` to obtain real session cookies, calls `dispatcher.deactivate()` and persists it, then issues `GET /api/mqtt/credentials` with the stale session cookie and asserts `HttpStatus.UNAUTHORIZED` -- followed by a second identical request (triangulation) asserting the same 401, proving the session is genuinely destroyed rather than rejected once by chance. This is the exact test the prior report's CRITICAL finding recommended, mirroring `DispatcherDeactivationSessionInvalidationTest`'s pattern but targeting the actual endpoint the scenario is about. Independently re-ran it in isolation (forced fresh, Docker cold-started for the run): BUILD SUCCESSFUL, JUnit XML confirms tests=1 failures=0 errors=0. This closes the change's sole CRITICAL finding.

### Correctness (Static plus Runtime Evidence)
| Requirement/Area | Status | Notes |
|------------|--------|-------|
| Dispatcher session auth (Argon2, form login) | Implemented, tested | Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); DispatcherSessionAuthenticationTest proves login/reject/me-resolution |
| Session cookie HttpOnly/Secure/SameSite=Strict | Implemented, tested | application.yml server.servlet.session.cookie; asserted directly in DispatcherSessionAuthenticationTest |
| Per-request orgId resolution | Implemented, tested | CurrentDispatcher/AuthenticatedDispatcher; DispatcherSessionAuthenticationTest resolvesTheAuthenticatedDispatcherOrganizationOnEveryRequest |
| Role-based method security (DISPATCHER/FLEET_ADMIN) | Implemented, tested | hasRole('FLEET_ADMIN') on all 3 device-credential endpoints; exercised as FLEET_ADMIN by every device-credential test |
| Session invalidation on deactivation | Implemented, tested | DeactivatedDispatcherSessionFilter; DispatcherDeactivationSessionInvalidationTest (2 tests, incl. triangulated stays-rejected-on-retry check) |
| MQTT credential renewal blocked after session revocation (WU4-verify-remediation) | Implemented, tested, independently verified correct | Same DeactivatedDispatcherSessionFilter + anyRequest().authenticated() chain, now proven directly against GET /api/mqtt/credentials by MqttCredentialsAfterDispatcherDeactivationTest, with retry triangulation |
| Browser ephemeral MQTT credentials plus org-scoped ACL | Implemented, tested | BrowserMqttCredentialService; ACL is fleet/{orgId}/# subscribe-only, proven by the flagship isolation test |
| Scheduled purge of expired browser credentials | Implemented, tested | ExpiredMqttCredentialPurgeTask (60s fixedDelay); ExpiredBrowserCredentialConnectionTest invokes purge directly, not timer-dependent |
| Device credential provisioning, vehicle-scoped ACL | Implemented, tested | DeviceCredentialService.provision; publish on telemetry+status, subscribe on command only |
| Device credential revocation, forced disconnect | Implemented, tested | deleteClient force-disconnects live sessions (Mosquitto's own behavior); proven with a real open connection plus CountDownLatch |
| Device credential rotation | Implemented, tested | Old credential invalidated, device row untouched, new credential scoped identically |
| Mosquitto auth/ACL backend (dynamic-security) | Implemented, tested | MosquittoDynamicSecurityAdminClient drives $CONTROL/dynamic-security/v1; confirmed deviation from design.md literal wording (own JSON-backed store vs Postgres queried directly), user-approved per tasks.md, not re-flagged as a blocker |
| Anonymous access denied, both listeners | Implemented, tested | Single allow_anonymous false in docker/mosquitto/mosquitto.conf, applies broker-wide; tested against both TCP and WebSocket |
| CSRF protection for JSON SPA mutation endpoints (WU4 fix) | Implemented, tested, independently verified correct | csrf().spa() is Spring Security's built-in SPA CSRF recipe; DispatcherLoginTestSupport correctly parses cookies by name; all mutation-endpoint device-credential tests pass through this path |
| LazyInitializationException fix (WU4) | Implemented, tested, independently verified correct | @Transactional correctly placed on DeviceCredentialController public entry points, not the private helper it self-invokes |

### Coherence (Design)
| Decision | Followed? | Notes |
|----------|-----------|-------|
| Topic hierarchy fleet/{orgId}/vehicle/{vehicleId}/... | Yes | Used identically across browser ACL, device ACL, and all tests |
| Server-side session (not self-contained token) for dispatchers | Yes | Spring Session JDBC; enables the immediate-invalidation-on-deactivation guarantee, now proven end-to-end for MQTT credential renewal too |
| Ephemeral, session-derived browser MQTT credentials | Yes | Short TTL (PT5M in local/test config), org-scoped read-only ACL |
| Accepted revocation-lag bounded by TTL for browser credentials | Yes, with an added nuance | design.md bounds the lag by credential TTL; in practice the lag is TTL plus up to 60s because of ExpiredMqttCredentialPurgeTask's fixed interval (see WARNING below); the session-revocation path itself (this scenario) is now proven to reject renewal on the very next request, matching design intent |
| Device credentials: long-lived, vehicle-scoped, individually revocable | Yes | DeviceCredentialService; immediate broker-side force-disconnect on revoke |
| ACL enforcement lives in the broker, not the application | Yes | Confirmed by the flagship isolation test connecting a real Paho client directly to Mosquitto |
| Mosquitto validates credentials/ACL against the backend store | Confirmed deviation (user-approved, not re-flagged) | Uses the plugin's own JSON-backed dynamic-security store, pushed into by the backend admin client, rather than the broker querying Postgres directly -- the deviation already documented and approved in tasks.md |

### Issues Found

**CRITICAL** (0): none. The prior CRITICAL finding (untested scenario "Renovacion tras revocar la sesion del despachador") is confirmed resolved: MqttCredentialsAfterDispatcherDeactivationTest was independently read, its assertions confirmed to target GET /api/mqtt/credentials with a 401 UNAUTHORIZED expectation plus retry triangulation, and independently re-executed with a fresh, cold-start run to BUILD SUCCESSFUL / 1/1 passed.

**WARNING** (3), carried over from the prior verify run, unaffected by commit 3b4bace (no production code changed):
1. ProcessorHeartbeatPublisherTest.publishesARetainedHeartbeatWithAFreshTimestamp (and, per the prior report, ProcessorHeartbeatHealthIndicatorTest.reportsDownWhenTheRetainedHeartbeatIsStale) are flaky under full-suite Testcontainers/Docker load -- this run reproduced the same flake twice in 3 attempts, always passing cleanly in isolation. Pre-existing, unrelated to this change's file set. Non-blocking; recommend raising these two tests' fixed timeouts or consolidating/reusing broker containers across test classes to reduce full-suite Docker load.
2. ExpiredMqttCredentialPurgeTask's 60s fixedDelay adds up to about 60s of lag beyond design.md's literal "bounded by TTL" wording for the browser-credential-expiry path specifically (distinct from the session-revocation-lag path, which design.md explicitly accepts and which is now proven immediate at the HTTP layer). Low practical severity given the short TTL already in use. Still open; no code change observed since the prior report.
3. application.yml's comment justifying unauthenticated management.endpoint.health.show-details: always ("No Spring Security yet (change 02-add-fleet-auth)...") is stale, since this exact change adds Spring Security. Still present verbatim at backend/api/src/main/resources/application.yml:37. Low severity, informational; the exposure itself may be an acceptable tradeoff, but the comment should be updated to reflect the post-change state.

**SUGGESTION**: None beyond the above.

### TDD Compliance
| Check | Result | Details |
|-------|--------|---------|
| TDD Evidence reported | Yes | Engram sdd/02-add-fleet-auth/apply-progress (observation #87) documents the verify-remediation RED/GREEN cycle: new test run in isolation first, observed GREEN on first run (expected, since the mechanism was already correct -- no production code changed) |
| All tasks have tests | Yes | 24/24 numbered work tasks plus the new 6.8 all have direct, independently-read test coverage; 0 untested spec scenarios remain |
| RED confirmed (tests exist) | Yes | MqttCredentialsAfterDispatcherDeactivationTest.java was read in full during this verification; all previously-cited test files still exist |
| GREEN confirmed (tests pass) | Yes | 105/105 tests pass in the final clean run; the new test was independently re-run twice in this session (isolated, then as part of the full suite) |
| Triangulation adequate | Yes | The new test itself triangulates (deactivate then reject then retry with same stale cookie then still reject), mirroring the pattern already used by DispatcherDeactivationSessionInvalidationTest |
| Safety Net for modified files | Yes | Full api:test module (47 tests, all pre-existing WU2/WU3/WU4 tests plus the new one) passes together; no regressions introduced by the 2-file remediation commit |

**TDD Compliance**: 6/6 checks passed.

### Test Layer Distribution
| Layer | Tests | Files | Tools |
|-------|-------|-------|-------|
| Integration (real Testcontainers Postgres/PostGIS or Mosquitto, no mocks) | 105 | about 31 test classes across 4 modules | JUnit 5, Testcontainers, AssertJ, Paho MQTT client, Spring TestRestTemplate |
| Unit | 0 (this change) | -- | -- |
| E2E (browser) | 0 | -- | Out of scope (Angular console client wiring is a later change) |

### Assertion Quality
No trivial assertions found, including in the new test file. MqttCredentialsAfterDispatcherDeactivationTest asserts distinct, meaningful expected values (HttpStatus.UNAUTHORIZED) against a real HTTP round-trip through the full Spring Security filter chain, with a deliberate retry to rule out a one-shot false pass. Every other reviewed test file from the prior verify pass (CrossOrganizationMqttIsolationTest, DeviceTelemetryVehicleIsolationTest, DeviceAlertsSubscriptionRejectionTest, DeviceRevocationForcedDisconnectTest, ExpiredBrowserCredentialConnectionTest, MosquittoAnonymousConnectionRejectionTest, DispatcherDeactivationSessionInvalidationTest) remains unchanged and was re-executed successfully in this run.

**Assertion quality**: All assertions verify real behavior.

### Security Posture Assessment (explicit, as requested)
- Argon2 hashing: sound. Used consistently for both dispatcher passwords and MQTT credential passwords via a single shared PasswordEncoder bean.
- HttpOnly/Secure/SameSite=Strict cookies: sound and directly asserted at runtime, not just configured.
- Anonymous-deny on both Mosquitto listeners: sound. A single broker-wide allow_anonymous false directive, tested against both TCP and WebSocket independently.
- Org-scoped ACLs (browser): sound. Read-only, wildcard confined to the dispatcher's own orgId; proven by the flagship cross-org test against a real broker.
- Device-scoped ACLs: sound. Publish confined to the device's own vehicle telemetry/status topics, subscribe confined to its own vehicle command topic; both positive and negative cases proven with real delivery/non-delivery observation.
- Immediate device revocation: sound. Verified with a genuinely open MQTT connection force-closed mid-session.
- Session-revocation blocks MQTT credential renewal: now sound and proven, closing this change's only remaining gap. GET /api/mqtt/credentials sits behind the same generic anyRequest().authenticated() chain as every other authenticated endpoint; a deactivated dispatcher's stale session is rejected on the very next request, with no endpoint-specific bypass.
- CSRF (WU4 fix): sound. Uses Spring Security's own built-in SPA recipe rather than a custom workaround.
- Residual risk: none CRITICAL. The two open WARNINGs (purge-cycle lag beyond literal TTL wording; stale application.yml comment) are narrow, low-severity, and informational -- neither indicates unsound design.

### Verdict
PASS WITH WARNINGS -- 0 CRITICAL findings, 3 WARNINGs (all pre-existing/informational, none blocking), 0 SUGGESTIONs. All 26/26 tasks complete (25 original plus verify-remediation task 6.8), 8/8 spec scenarios COMPLIANT (up from 7/8 in the prior verify run), 105/105 executed tests pass in the final clean run (up from 104/104, additive with zero regressions). The prior verify run's sole CRITICAL finding -- the untested "Renovacion tras revocar la sesion del despachador" scenario -- is independently confirmed resolved: MqttCredentialsAfterDispatcherDeactivationTest was read in full, its assertions verified to target the correct endpoint and status code with retry triangulation, and independently re-executed to a fresh BUILD SUCCESSFUL. This change is ready for sdd-archive.
