# Global SYSADMIN operations (#91)

Implements [ADR 0016](adr/0016-cross-organization-sharing.md), decision 1.
`SYSADMIN` is a global account privilege in `app_user_system_roles`, separate
from organization memberships. Organization `ADMIN` (displayed as **Orgaadmin**)
and `MEMBER`, and `PENDING | ACTIVE | DISABLED` states, retain their existing
semantics. V11 creates empty role and audit tables; it promotes nobody.

## Initial setup

1. Start the backend normally to apply Flyway V11. On an empty installation,
   use the existing [organization bootstrap](bootstrap.md) to create the first
   account with a BCrypt password. That account is an Orgaadmin only.
2. Confirm the intended account can log in through the normal password/session
   flow. Obtain its `app_users.id` from your trusted database administration
   session (or its authenticated `/api/v1/auth/session` response).
3. From a trusted server-operator shell, run the packaged application in
   explicit maintenance mode. Supply `DB_URL` (PostgreSQL JDBC URL), `DB_USER`
   and `DB_PASSWORD` through your deployment's secret/environment mechanism.
   Maintenance has no default database credentials. Never put passwords in
   the command line, audit reason, shell history or source control.

```text
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar sysadmin GRANT <operation-uuid> <user-uuid> <operator-identity> "Initial system administrator; ticket OPS-123"
```

Generate a fresh UUID for the operation and record it in your operator ticket.
Use a meaningful operator identity and reason (required, maximum 255 and 500
characters respectively). The operator identity is an audit assertion by the
trusted server operator; it is not an alternative application login credential.
Restrict host execution, database credentials and database writes to authorized
operators. Maintenance pins the connection's search path to `public`, matching
the application's database configuration.

This command runs **before Spring starts**, opens no HTTP listener, performs no
organization bootstrap or schema migration, and exits after one atomic database
transaction. It grants only an existing ACTIVE account; unknown or disabled
accounts, invalid commands, missing environment and unmigrated schemas fail
closed with a nonzero process exit. It creates no account and changes no password,
organization or membership. Normal application startup never grants SYSADMIN.

## Revocation and recovery

```text
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar sysadmin REVOKE <new-operation-uuid> <user-uuid> <operator-identity> "Access revoked; ticket OPS-124"
```

Revocation also works for disabled accounts. Global authorization checks query
both the role and the current account status on every request, including existing
sessions. Disabling an account immediately blocks SYSADMIN; its stored grant is
retained, so re-enabling the account restores access unless explicitly revoked.
For permanent removal, issue REVOKE as well. Existing tenant/account-disabling
behavior is unchanged.

To recover a revoked grant, run GRANT with a **new** operation UUID for an existing
ACTIVE account after operator approval. Recovery uses that account's normal
password login. It does not bypass authentication or reactivate a disabled user.
If all available accounts are disabled or their passwords are lost, first follow
your trusted account/password maintenance procedure; this command deliberately
does not introduce a password-reset or unauthenticated recovery backdoor.

Account row locking serializes changes for each user. Grant/revoke and audit are
committed together. Repeating the exact command with the same operation UUID is
a no-op, even after a later revocation: replaying initial bootstrap never restores
revoked authority. Reusing an ID for different inputs fails. Fresh no-op commands
are audited with `changed=false`. Keep the ledger; deleting operation IDs defeats
replay protection. Production code only appends audit records.

```sql
SELECT operation_id, user_id, role, action, operator, reason, changed, created_at
FROM public.app_user_system_role_audit ORDER BY created_at, operation_id;
```

## Authorization boundary

`SystemAuthorizationService.isSysadmin` checks the isolated role and ACTIVE user
with one database query. `requireSysadmin` is the reusable service-level gate;
call it with the authenticated `AppUserDetails`, never an ID taken from a request.
Spring Security reserves `/admin/organizations` and `/admin/organizations/**`
for this global gate ahead of the existing tenant admin matcher. No organization
management controller is shipped; #92 will implement that functionality and
must also gate its service operations. Existing `/admin/**` organization pages
continue to require ACTIVE ADMIN membership and the validated selected context.

SYSADMIN does not satisfy `TenantService.requireActive` or `requireAdmin`.
Cats, feeding sites, nodes, observations and visits still require the existing
organization checks. Multiple memberships stay independent. Passwords, sessions,
CSRF, login/logout, AppDevice authorization and offline BLE credential claims
are unchanged: credentials contain only the selected membership's ADMIN/MEMBER
role and cannot authorize another organization's node. The global role is not
cached in session authorities or exposed as a tenant role.

Focused integration tests are `SystemPrivilegeTest` (auth/system/schema tags),
run with `python scripts/test.py backend --test SystemPrivilegeTest`. They use
test-only boundary probes; no production demonstration endpoint is added.
