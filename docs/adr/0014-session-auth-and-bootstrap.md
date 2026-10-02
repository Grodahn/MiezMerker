# 0014 — Backend session authentication and test-phase bootstrap
Status: Accepted (implements #16).

## Context

#16 requires email/password login for the same-origin PWA, server-side tenant isolation,
and a reproducible test-phase bootstrap without secrets in git.

## Decision

**Server-side session (Spring Security) + BCrypt password hashing + CSRF cookie token.**

- `BCryptPasswordEncoder` (adaptive, strength 12). No plaintext passwords, no custom
  password crypto.
- Login establishes an HTTP session; `JSESSIONID` cookie is `HttpOnly`, `SameSite=Lax`,
  `Secure` by default (HTTPS production); local HTTP opts out with `COOKIE_SECURE=false`.
  The servlet session timeout is 12h.
- CSRF: `CookieCsrfTokenRepository.withHttpOnlyFalse()`; the SPA reads the `XSRF-TOKEN`
  cookie and sends `X-XSRF-TOKEN` on state-changing requests. Login/logout/mutations are
  CSRF-protected; public GETs are not.
- Programmatic login invokes Spring Security's session authentication strategy, rotates
  the session id and CSRF token, and saves a fresh security context. Logout clears both
  the server session and CSRF cookie. The PWA fetches a fresh token before login/logout
  and keeps the signed-in state when the server rejects logout.
- Unauthenticated API access returns `401`; failed login returns a generic `401` (no
  account enumeration).
- No public self-registration. The first organization + admin are created by
  `BootstrapRunner` on an empty database from environment variables
  (`BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` min 12 chars / max 72 UTF-8 bytes, optional
  `BOOTSTRAP_ORG_SLUG/_NAME/_CONTACT`). Bootstrap is idempotent and stores only the
  BCrypt hash. See `docs/bootstrap.md`.

## Tenant isolation

`TenantService.requireActive/requireAdmin` resolves the authenticated user's membership
server-side for every organization-scoped operation. A client-supplied `organizationId`
is never authority. `PENDING`/`DISABLED` memberships get `403`; cross-tenant access gets
`403`/`404` (never cross-tenant writes).
Organization listing exposes only ACTIVE memberships in ACTIVE organizations. Disabled
accounts are checked against the database on each protected request, including existing
sessions. The member roster requires ADMIN. Current-session membership status remains
available for onboarding, while disabled organizations are excluded from selection.

## Membership model

`OrganizationMembership` separates `role` (`ADMIN|MEMBER`) from `status`
(`PENDING|ACTIVE|DISABLED`). `PENDING` is a status, not a role. A user may belong to
multiple organizations; the PWA works in an explicit active organization context.

## Deferred

#19 (invite-by-email activation) replaces bootstrap later. The schema already supports
`PENDING` memberships and the architecture keeps invite tokens out of this MVP.
