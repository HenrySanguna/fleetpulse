# User management (create dispatcher/admin accounts from the app)

## Objective
Let a FLEET_ADMIN create DISPATCHER and FLEET_ADMIN accounts for their own organization from the console, instead of inserting rows directly via SQL (current process for the two demo users, with no reusable seed script).

## Problem / evidence
Confirmed via code exploration (2026-09-22): no admin-facing user-management endpoint exists anywhere in `backend/api` (only read-only `GET /api/dispatchers/{id}` and `/me`). No email/SMTP infra exists. No admin/settings UI exists in the console (`apps/console/src/app/app.routes.ts` only has `login`, live-map, `geofences`, `alerts`, `activity`). The only role check in the whole backend is `@PreAuthorize("hasRole('FLEET_ADMIN')")` on the existing dispatcher-lookup endpoint.

## Product decisions (resolved with user, 2026-09-22)
1. **Password**: FLEET_ADMIN types the initial password directly in the creation form. No invite-by-email flow (no SMTP infra exists; out of scope). No forced-change-on-first-login, no self-service change-password — explicitly accepted tradeoff.
2. **Roles creatable**: FLEET_ADMIN can create both DISPATCHER and FLEET_ADMIN accounts, scoped to their own organization — asked explicitly via AskUserQuestion by the orchestrator session, user picked "admin crea ambos roles" over the more restrictive DISPATCHER-only option. (A background worker forked before this question was asked briefly mislabeled this as "not asked" based on its own stale inherited context — it wasn't in that worker's context, but it happened, verified directly in the orchestrator's own tool-call history. Do not re-flag this as unconfirmed.)

## Assumed default (NOT asked — reasonable, low-consequence, reversible; flagging so it's not mistaken for a confirmed decision)
3. **Org scope**: new user's organization is always the creating admin's own org (resolved server-side from the security context, never client-supplied) — same isolation pattern already enforced elsewhere (`hidesADispatcherFromAnotherOrganizationAsNotFound`). Creating new organizations is explicitly out of scope — not requested, no org-creation entrypoint exists today either, separate concern.

## Explicit MVP scope (avoid scope creep)
IN: create a user (email, password, role) + list existing users in the admin's own org (needed so the admin can see what already exists / avoid duplicates — minimal viable companion to "create").
OUT (not building unless separately requested): edit user, deactivate/reactivate user (the `User.deactivate()` method and `active` field already exist in the domain but have no endpoint — leave as-is), password reset/self-service change-password, cross-org user management, organization creation.

## Backend tasks (Spring Boot / Java)
- [ ] 1. `CreateUserRequest` DTO: email, password, role. Bean Validation (`@Email`, `@NotBlank`, password min-length policy — match whatever's implied by existing Argon2/login tests if any exist, otherwise a reasonable default).
- [ ] 2. `UserSummaryResponse` DTO (id, email, role, active, createdAt) — never expose the entity or password hash.
- [ ] 3. `POST /api/dispatchers`, `@PreAuthorize("hasRole('FLEET_ADMIN')")`: resolve creating admin's org from `SecurityContext` (never from the request body), hash password with the existing `Argon2PasswordEncoder` bean, `active=true`, role from request (DISPATCHER or FLEET_ADMIN). Clean 409 (not 500) on duplicate email — check existing `DataIntegrityViolationException` handling convention in the codebase, or pre-check via repository.
- [ ] 4. `GET /api/dispatchers` (or confirm best-fit path against existing controller naming — the entity is `User` but the existing controller/routes are named `dispatchers`, follow that convention), `@PreAuthorize("hasRole('FLEET_ADMIN')")`: list users in the admin's own org only.
- [ ] 5. Tests mirroring existing patterns (Testcontainers, `DispatcherRoleAuthorizationTest`-style): DISPATCHER gets 403 on both endpoints; FLEET_ADMIN can create DISPATCHER and FLEET_ADMIN; a newly created user can actually log in with the password that was set; list only returns same-org users (cross-org isolation); duplicate email returns 409 not 500.

## Frontend tasks (Angular 19+ console, standalone/signals/OnPush per project convention)
- [ ] 6. New feature folder `apps/console/src/app/features/users/` (or confirm actual monorepo path — earlier exploration found `apps/console/src/app/...`): pages for list + create (dialog or dedicated page — writer's call, keep it simple), service using `inject(HttpClient)` + signals, models mirroring the two backend DTOs exactly (front/back contract rule).
- [ ] 7. New lazy route (`loadComponent`), functional `CanActivateFn` role guard restricting it to FLEET_ADMIN (check whether a role guard already exists anywhere in `core/` before writing a new one).
- [ ] 8. Nav link visible only to FLEET_ADMIN (reuse whatever conditional-rendering pattern the existing nav already uses for the authenticated-user info block).
- [ ] 9. Typed reactive form (email, password, role select) — no template-driven forms.
- [ ] 10. i18n: check whether this console actually uses Transloco already (don't assume — verify against the real codebase) before hardcoding visible strings either way; follow whatever's already established.
- [ ] 11. Frontend tests mirroring the existing `*.spec.ts` convention (e.g. `auth.service.spec.ts`) for the new service/component.

## Verification
Functional checks per task (build + new/existing tests green), no explicit project-wide TDD directive found — matching the rest of the repo's practice of tests alongside implementation, not asserting formal RED-before-code ceremony without a confirmed signal to do so.

## Authorized scope
User explicitly authorized both the "fix everything so the system works" directive and this new feature, and answered the one blocking product question above (2026-09-22).

## Status
**Paused** — user explicitly deprioritized this (2026-09-22): fix the live-map WebSocket exposure first, verify the whole system works, then come back to this. Not started. When resumed: backend work first (tasks 1-5), then frontend (6-11), each as its own work-unit commit.
