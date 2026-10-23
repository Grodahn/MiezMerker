# Selective, compact testing

Run commands from the repository root. The compact runner needs Python 3.10+
(standard library only), plus the subsystem's usual Java/Node/CMake tools.
Install frontend dependencies with `npm ci` in `app`. Configure and build
firmware before running CTest. No CI workflow or production behavior is changed.

## Compact command interface

The commands below work in Windows PowerShell and POSIX shells with `python`
on PATH. On Linux installations that name Python `python3`, replace the prefix
`python` with `python3`. Arguments after `--domain` select a **union**, so a test
tagged with both domains runs once. Unknown domain names are rejected, including
an unknown name mixed with a valid name.

Backend `--test` accepts one exact class name (optionally package-qualified),
or `Class#method`. Empty strings, wildcards and composite Surefire expressions
are rejected: Surefire can silently ignore unmatched members of a composite
selection. Use domains for unions or separate invocations for specific classes.

```text
python scripts/test.py backend --test NodeDisplayNameTest
python scripts/test.py backend --test 'NodeClaimTest#memberCannotClaim'
python scripts/test.py backend --domain node
python scripts/test.py backend --domain auth
python scripts/test.py backend --domain node auth
python scripts/test.py backend

python scripts/test.py frontend src/collector/ble-codec.test.ts
python scripts/test.py frontend src/collector
python scripts/test.py frontend src/platform
python scripts/test.py frontend src/management src/App.test.tsx
python scripts/test.py frontend

cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
python scripts/test.py firmware --label ble
python scripts/test.py firmware --label 'auth|lifecycle'
python scripts/test.py firmware --test '^node-identity$'
python scripts/test.py firmware

cmake -S firmware-esp32/tests -B build/offline-auth -DCMAKE_BUILD_TYPE=Release
cmake --build build/offline-auth --parallel
python scripts/test.py firmware --build-dir build/offline-auth --label auth
```

For multi-configuration generators, build with `--config Release` and pass
`--config Release` to the firmware runner. `--build-dir` is relative to the
calling shell's directory. CTest regexes are native CTest selection; Vitest paths
are passed directly to Vitest without custom file-selection logic.

Backend tags cover all existing test classes:

| Domain | Test classes |
| --- | --- |
| node | NodeClaimTest, NodeDisplayNameTest, DeploymentAuthorizationTest, AdminNodesTest, AdminSitesTest, NodeProvisionSetupTest |
| auth | NodeClaimTest, NodeDisplayNameTest, DeploymentAuthorizationTest, OfflineCredentialTest, IssuerConfigurationTest, InteropVectorTest, TenancyAuthTest, CookieSecurityTest, BootstrapTest, DisplayNameTest, FoundationTest, AdminCatsTest, AdminMembersTest, AdminObservationsVisitsTest, AdminShellTest, AdminNodesTest, AdminSitesTest, AdminOrganizationsTest, NodeProvisionSetupTest, OrganizationSharingTest, SharedCareTest, CatPhotoTest, CrossOrgSecurityAcceptanceTest |
| ingest | Issue9IngestTest, AdminObservationsVisitsTest |
| visits | Issue10VisitsTest, Issue100CatVisitsTest, FeedingSiteCatActivityTest, AdminCatsTest, AdminObservationsVisitsTest, AdminObservationsVisitsControllerTest, SharedCareTest, CrossOrgSecurityAcceptanceTest |
| admin | AdminCatsTest, AdminMembersTest, AdminObservationsVisitsTest, AdminShellTest, AdminNodesTest, AdminSitesTest, AdminObservationsVisitsControllerTest, AdminOrganizationSettingsTest, AdminOrganizationsTest |
| schema | DatabaseSchemaTest, DisplayNameTest, NodeDisplayNameTest, SystemPrivilegeTest, SystemRoleMigrationTest |
| system | FoundationTest, SystemControllerTest, SystemPrivilegeTest |

`TemporalVisitAggregationTest` and `TemporalProjectionEngineTest` belong to `visits`;
`AdminTemporalPresentationTest` belongs to `admin`, `auth`, and `visits` and covers
bounded page projection, stable node lock order, tenant-scoped name lookup and
outward minute rounding of phone estimates (#182). HTTP regressions in
`AdminObservationsVisitsTest` and `TimeEvidenceTest` cover separate raw/projected
rendering, late evidence, historical deployment changes and uncertain time.
`TimeEvidenceTest` belongs to `node`, `auth`, and `visits` and covers #132/#133
evidence, temporal history, tenant isolation, and the explicit capacity limit.

`SystemPrivilegeTest` also belongs to the `auth` domain.
`NodeTimeBootstrapTest` and `NodeTimeProtocolTest` belong to `node` and `auth` and
cover signed UTC bootstrap, authorization, claim races, bounded abuse controls,
and the ADR 0017 challenge/proof/acceptance interoperability fixtures.
`DevDemoTest` and `DevDemoCommandTest` belong to `auth`, `system`, and `schema` and cover the explicit
local demo fixture, ownership collisions, login/API access and replay safety.
`AdminOrganizationSettingsTest` also belongs to the `auth` domain and covers the rendered sharing settings,
CSRF, organization switching, recipient privacy and concurrent revocation.
`SystemRoleMaintenanceTest` belongs to `auth` and covers failure cleanup of
operator transactions.
`MembershipConflictHandlerTest` belongs to `admin` and `auth`: concurrent membership
updates return a non-cacheable 409 without exposing persistence details.
`AdminOrganizationsTest` additionally verifies stale tenant/SYSADMIN writes,
atomic audit rollback, concurrent duplicate grants, and actual organization switches
(including a disabled selected organization). V23 adds optimistic membership
versions without changing existing identities, roles, statuses or associations.

CTest labels are `ble`, `auth`, `storage`, `lifecycle`, and `simulator`.
Labels overlap where appropriate. Scenario labels follow the numbered scenario
contracts in `simulator/CMakeLists.txt`; update that mapping when adding scenarios.
Use `ctest --test-dir build --show-only=json-v1` to inspect full discovery and labels.
The standalone offline-auth build remains a separate suite as in existing CI.

## Native commands

The underlying tools remain available. Maven's configured `failIfNoTests` and
`failIfNoSpecifiedTests` reject empty selections. Direct Maven tag expressions
support JUnit's syntax; unlike the wrapper, Maven cannot catch an unknown tag
combined with a valid tag in a union, so prefer the wrapper for domain selection.

PowerShell:

```powershell
Push-Location backend
./mvnw.cmd -B -ntp test '-Dtest=NodeDisplayNameTest'
./mvnw.cmd -B -ntp test '-Dgroups=node'
./mvnw.cmd -B -ntp test '-Dgroups=auth'
./mvnw.cmd -B -ntp test '-Dgroups=node,auth'
./mvnw.cmd -B -ntp test
Pop-Location
Push-Location app
npm test -- src/collector/ble-codec.test.ts --reporter=dot
npm test -- src/collector --reporter=dot
npm test -- src/platform --reporter=dot
npm test -- src/management src/App.test.tsx --reporter=dot
npm test -- --reporter=dot
Pop-Location
ctest --test-dir build -L ble --no-tests=error --output-on-failure
ctest --test-dir build --no-tests=error --output-on-failure
```

POSIX:

```sh
(cd backend && ./mvnw -B -ntp test '-Dtest=NodeDisplayNameTest')
(cd backend && ./mvnw -B -ntp test '-Dgroups=node')
(cd backend && ./mvnw -B -ntp test '-Dgroups=auth')
(cd backend && ./mvnw -B -ntp test '-Dgroups=node|auth')
(cd backend && ./mvnw -B -ntp test)
(cd app && npm test -- src/collector/ble-codec.test.ts --reporter=dot)
(cd app && npm test -- src/collector --reporter=dot)
(cd app && npm test -- src/platform --reporter=dot)
(cd app && npm test -- src/management src/App.test.tsx --reporter=dot)
(cd app && npm test -- --reporter=dot)
ctest --test-dir build -L ble --no-tests=error --output-on-failure
ctest --test-dir build --no-tests=error --output-on-failure
```

## Output, reports, and exit status

Illustrative success:

```text
PASS backend/node
Tests: 42
Duration: 31.4s
```

Illustrative assertion failure:

```text
FAIL backend/node
Tests: 42 | Failed: 1
Duration: 31.4s

Failures:
- org.miezmerker.backend.NodeClaimTest.memberCannotClaim
expected: <403> but was: <200>

Detailed logs: .../test-results/compact/<run>/output.log
Reports: .../test-results/compact/<run>
```

Each invocation saves complete combined stdout/stderr and its exact command in
a unique, ignored `test-results/compact/<UTC timestamp>-<random>/` directory.
Surefire XML, Vitest JSON, or CTest JUnit XML is stored alongside the log.
Maven uses a fresh report directory without deleting earlier reports or reading
stale `backend/target/surefire-reports`. Concurrent Maven builds in the same
checkout are still unsupported because Maven shares compiled output.

Failures print test names, bounded messages and diagnostic log matches, including
compilation/startup errors, with paths to complete details. Successful output is
only three lines. To find and read the latest full log:

```powershell
$run = Get-ChildItem test-results/compact -Directory | Sort-Object LastWriteTime | Select-Object -Last 1
Get-Content (Join-Path $run.FullName output.log)
```

```sh
ls -1dt test-results/compact/*/   # newest run first
cat test-results/compact/<run>/output.log
```

The wrapper returns the native process exit code on failure, without output
piping. Missing tools return 127. A native success with missing/invalid reports,
reported failures, or zero executed tests becomes exit code 1. Skipped tests do
not count as executed. Unknown wrapper arguments/domains return 2. Inspect
`$LASTEXITCODE` in PowerShell or `$?` in POSIX immediately after execution.

## Development policy: risk review → focused development → integrated review → regression → CI

Follow the **0 → A → R → B → C** order below. This is a scheduling strategy,
**not** permission to reduce acceptance criteria, assertions, coverage, security
review, or existing CI gates. Use the existing compact runner and preserve its
failure and empty-selection semantics.

0. **Contract and risk map, before editing (0).** Read the authoritative
   issue/ADR, find the smallest set of implementation files and their actual
   consumers, and check ongoing work on shared interfaces. For auth, claims,
   BLE, storage and time handling, think through revocation, stale async
   results, reboot, interrupted persistence, replay, cross-tenant access and
   failure recovery **before writing code**. Plan focused tests and the
   affected-domain regression now. This is a concise working plan, not a
   second research issue or an excuse to delay implementation.
1. **Focused implementation loop (A).** Write code and use the smallest
   meaningful unit/integration test selection, including directly affected
   consumers outside the edited directory. On failure, fix and rerun the
   failing test first, then its affected group. Run TypeScript typechecking
   early when changing shared types/interfaces. Preserve complete logs and
   native failure status; avoid repeating broad frontend suites after edits.
2. **Integrated issue-contract/security review and repair (R).** Before
   declaring local completion, review the changed diff, its direct
   dependencies, invariants, edge cases, accepted issue scope, and test
   selection. Consolidate confirmed in-scope findings into **one repair
   phase** and run tests targeted at repaired behavior. Stop and report
   scope-expanding dependencies rather than implementing adjacent tickets.
   Revisit the *wider* review only for new contract/security/high-risk changes;
   small localized fixes do not automatically restart the entire review.
3. **Affected-domain local completion (B).** After the review/repairs
   stabilize, run **one** affected-domain regression selection, the necessary
   production build/typecheck/API checks, and relevant browser tests.
   Choose the selection by changed **behavior and consumers**, not just
   directory proximity. Tests previously passing do not validate later
   behavior changes automatically: use the decision rules below.
4. **Final-head GitHub CI and closeout (C).** Submit/push the PR and require
   the existing complete GitHub CI matrix, including applicable firmware,
   PostgreSQL, backend and browser/end-to-end checks, on the **exact final
   head SHA**. Inspect failed checks narrowly and fix confirmed in-scope
   errors. A changed head requires fresh CI; a green check from a previous
   SHA is not proof for the final commit. Once the required checks are green,
   complete the report/PR without unnecessary extra full-suite passes or
   minutes of post-green polling. Do not auto-merge unless requested.

### Which tests to rerun after a late fix?

- **Localized fix without shared-contract impact:** rerun the failing test
  and directly affected consumers. Reassess whether the existing completion
  regression remains representative; do not reflexively rerun an unchanged
  unrelated domain.
- **Auth/security, public API, cross-tab race, persistent data, migration,
  temporal projection, BLE protocol or other shared-contract change:**
  reevaluate the risk map/review and rerun the corresponding affected-domain
  regression, typecheck/build and browser coverage as needed. A previous B
  result does not cover a modified contract.
- **Upstream merge during a parallel implementation:** integrate relevant
  merged changes **before B** when feasible, inspect the combined diff, then
  verify affected integration behavior. If main moves after B, evaluate actual
  shared-file/interface impact before another local run; avoid automatic
  rebases solely because an unrelated commit landed. CI must still correspond
  to the final PR head SHA.
- **Failure in CI:** rerun the failing test locally if reproducible, fix it,
  then execute the affected checks and get fresh CI on the new commit. Never
  delete, skip, relax, or mock out failing required tests to gain a green run.

Example for auth changes: focused implementation tests can be

```text
python scripts/test.py frontend src/platform/auth.test.ts src/platform/auth-offline.test.ts src/collector/authorization.test.ts
```

After integration review and repairs stabilize, a broader affected-domain
selection may be

```text
python scripts/test.py frontend src/platform src/collector src/App.test.tsx
```

Include storage, upload, BLE cancellation and browser tests when the changed
authorization behavior reaches them. These examples do not replace the
issue-specific test matrix.

### CI waiting and agent prompts

Prefer a supported CI watch (for example `gh pr checks --watch`), when
available, rather than chains of fixed 60-second sleeps and repeated status
requests. If only polling is possible, space checks sensibly and avoid idle
delays after all final-head checks have passed. No CI timeout or missing
required check should be reported as success.

Use concise Codex task prompts; `AGENTS.md` and this file already own the
default workflow. Example:

```text
Implement Grodahn/MiezMerker#<issue> (issue is the authoritative contract).
Follow AGENTS.md and docs/testing.md, using 0→A→R→B→C:
map high-risk/security/integration cases before coding; focused tests during
implementation; one consolidated review-and-repair before affected-domain
completion; full GitHub CI on the final PR head.

Respect boundaries: <issue-specific dependencies and non-goals>.
Fix confirmed in-scope review and CI findings autonomously; report blockers
rather than broadening the issue. Do not pause between normal stages.
Open/update a PR but do NOT merge without explicit approval.
Report PR/SHA, actual tests and CI, review findings/fixes, remaining risks and
hardware validation as VERIFIED or UNVERIFIED. Be token-efficient; never
weaken tests or security gates for speed.
```

If `AGENTS.md` was already injected into the agent's context, do not reread
or reproduce it. Prompts should add **issue-specific constraints**, not
repeat the entire test plan, review checklist and CI procedure.

Runner regression checks (no Java/Node/C++ dependencies required):
`python -m unittest discover -s scripts/tests -v`.

`NodeBowlNumberTest` belongs to `node` and `schema`: deterministic pre-V21
backfill, concurrent sequence allocation, immutable UUID, non-reuse on deletion
and the claim-receipt byte budget.
`ClaimReceiptBudgetTest` belongs to `node` and `auth`: serialized UTF-8/escaping
budgets and the signed ESP32 receipt interoperability fixture (#146).

`CollectorSyncEventTest` belongs to `node`, `auth`, and `ingest`: collector-only
success reports, owner/tenant/admin gates, historical deployment uncertainty,
current recipient authorization, idempotency/concurrent retries and retention.

`DiagnosticErrorTest` belongs to `auth`, `admin`, and `system`: safe structured
HTTP errors, neutral tenant denials and readable admin exception pages (#194).
`python scripts/generate-error-codes.py --check` validates the shared diagnostic
catalog and generated TypeScript/Java/static-JavaScript/support adapters.

`SecurityClockPolicyTest` belongs to `node` and `auth`: default alpha, explicit signed
configuration and fail-closed release/invalid policy. `NodeClaimTest` explicitly
checks alpha receipt issuance with UTC-bootstrap health disabled (#195).
