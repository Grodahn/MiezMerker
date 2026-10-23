# Issue #194 review and validation

## Contract and risk map (0)

The append-only catalog owns semantic case IDs; generated TypeScript, Java,
static presenter and support table share it. Sources select codes from structured
kinds, protocol statuses, HTTP status and controller operation, never translated
text. Legacy string states carry explicit catalog markers to the renderer.
Direct consumers: shared Status, batch/single collector views, registration,
setup, account, management/photo, shared-care presenter, API and admin templates.
No open parallel PR was found; origin/main was checked before completion.

Security/race review: preserve permission/tenant gates, account and org fences,
credential lifetime, secure-vs-Alpha policy, persisted-before-ACK ordering,
immutable upload identity and committed-ACK/COMPACT separation. No storage schema,
wire value, retry decision or permission is changed. Unknown server exceptions
are reduced to data-free envelopes; browser and batch unknown details use safe
fallbacks. Typed diagnostic errors retain their curated readable messages.
HTTP denials keep statuses and deliberately neutral messages/codes. Existing
safe diagnostic headers (including revocation and time bootstrap) remain intact.
A code is a stable case ID, not a per-incident correlation ID.

## Integrated review and repairs (R)

- Preserve exact readable management messages by rendering the extracted primary
  code separately; never infer codes from wording.
- Carry structured API codes through management, definitive upload denials and
  claim failures; distinguish immutable conflicts and evidence/report queues.
- Separate Alpha-policy/rejection, phone snapshot/reboot and signed-time phases.
- Cover failure notices rendered as info; preserve normal cancellation/waiting
  semantics and avoid codes on unqualified success messages. COMPACT/metadata
  warnings retain their own code alongside an already successful local copy.
- Keep specific membership/media/time advice ahead of the general safe handler;
  cover servlet fallback HTML and authentication/CSRF failures as well as MVC.
- Include the generated static catalog in Vite Admin proxy routing and browser
  harnesses, preserving session gates in preview/development.
- Document default API errors without replacing successful response schemas;
  operation-scoped validation/conflict/unavailable codes improve diagnostics,
  while security denials remain neutral across operations and tenants.

## Verification selection (A/B/C)

Focused checks cover catalog uniqueness/stability, registration identity,
secure/Alpha time, BLE status mapping, storage/ACK, upload/outbox, API errors,
neutral tenant denials, unknown details and admin rendering. Completion selects
all frontend consumers and backend auth/admin/ingest/system domains (auth also
covers node/time/tenant boundaries); build/typecheck, generated OpenAPI drift and
browser flows follow. Existing full final-head CI remains required, including
PostgreSQL, end-to-end and firmware/security matrices. Actual executed outcomes
and final SHA are recorded in the PR; this file does not predeclare green CI.

Hardware acceptance: **UNVERIFIED**. No physical firmware/protocol changes.
The realistic identity-error text fixture is in [error-codes.md](error-codes.md);
the browser blocker flow also captures an automated error-code screenshot.

Efficiency: initial Windows wildcard/path mistakes and encoding/temp-directory
issues were corrected; use scoped rg globs, explicit UTF-8 and the working
test environment. No test expectation or required gate was removed or relaxed.

Local Windows diagnostic note: default-limit aggregate frontend runs encountered
resource timeouts. An isolated App run completed all 41 tests; its first module
load took 5.84 seconds, exceeding the unchanged 5-second test limit. The final
local aggregate uses two workers and a 20-second CLI budget; no repository/CI
limit, assertion, test selection or coverage is relaxed. Successful final-head
CI with the existing default limits is still required. API fixtures were updated
to structured errors while preserving their auth and privacy assertions.
