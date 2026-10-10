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
| auth | NodeClaimTest, NodeDisplayNameTest, DeploymentAuthorizationTest, OfflineCredentialTest, IssuerConfigurationTest, InteropVectorTest, TenancyAuthTest, CookieSecurityTest, BootstrapTest, DisplayNameTest, FoundationTest, AdminCatsTest, AdminMembersTest, AdminObservationsVisitsTest, AdminShellTest, AdminNodesTest, AdminSitesTest, NodeProvisionSetupTest, OrganizationSharingTest |
| ingest | Issue9IngestTest, AdminObservationsVisitsTest |
| visits | Issue10VisitsTest, Issue100CatVisitsTest, FeedingSiteCatActivityTest, AdminCatsTest, AdminObservationsVisitsTest, AdminObservationsVisitsControllerTest |
| admin | AdminCatsTest, AdminMembersTest, AdminObservationsVisitsTest, AdminShellTest, AdminNodesTest, AdminSitesTest, AdminObservationsVisitsControllerTest |
| schema | DatabaseSchemaTest, DisplayNameTest, NodeDisplayNameTest, SystemPrivilegeTest, SystemRoleMigrationTest |
| system | FoundationTest, SystemControllerTest, SystemPrivilegeTest |

`SystemPrivilegeTest` also belongs to the `auth` domain.
`SystemRoleMaintenanceTest` belongs to `auth` and covers failure cleanup of
operator transactions.

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

## Development policy

1. **A — Implementation loop:** Run the smallest affected selection. After a fix,
   rerun the failing test first. Read full logs only when compact diagnostics are
   insufficient; do not run the whole repository matrix after every edit.
2. **B — Local completion:** Once changes stabilize, run relevant domain
   regressions once. Run typechecks/build/API consistency checks when changed
   interfaces require them.
3. **C — GitHub CI:** Let existing CI run the broad regression matrix. Do not
   repeatedly duplicate full CI locally without a concrete risk or failure.
   Investigate failed jobs through failed tests and relevant logs, without
   downloading all successful job logs.

Runner regression checks (no Java/Node/C++ dependencies required):
`python -m unittest discover -s scripts/tests -v`.
