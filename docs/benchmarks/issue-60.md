# Issue #60: reuse the real session acceptance-test context

Contract: [#60](https://github.com/Grodahn/MiezMerker/issues/60). Base: `a1f3424`
(current main, including #58/#59/#61, #129 and #132 via #141). Firmware #130
is outside this change. No production code, Maven settings, CI selection,
password strength, database cleanup, test tags or security settings change.

## Decision and architecture inventory

The inventory contains 41 test classes: 29 `RANDOM_PORT`, three default/mock
`@SpringBootTest` classes, six plain JUnit classes (including a controller test
using Mockito and mock servlet objects), two direct JDBC/Flyway integration
classes and one standalone demo-command integration class that explicitly
starts Boot twice. There are no `@WebMvcTest`, `@DataJpaTest` or MockMvc tests.
The three non-server Boot classes are BootstrapTest, DatabaseSchemaTest and
PasswordEncoderStrengthTest. Existing pure tests already cover protocol,
cryptographic configuration, temporal interval mathematics and simple controller
behavior without starting an application. Do not count all non-annotated classes
as pure units: the migration, maintenance and demo-command tests use real DBs.

Most full HTTP classes share the test-profile context. Cache differences are
caused by imported probes/demo services, media properties, spy beans and the
special #132 signer/uncertainty configuration. The last intentionally closes its
context after the class to release PostgreSQL connections. Fixtures use #59's
FK-safe bulk cleaner and real BCrypt cost 4. Neither is reworked here.

| Candidate | Baseline class seconds in full H2 run | Fidelity / decision |
| --- | ---: | --- |
| Issue9IngestTest | 5.192 | RETAINED: concurrent HTTP uploads/registration, real transaction races, immutable observations, historical deployment and tenant negatives. |
| Issue10VisitsTest | 3.341 | RETAINED: real ingest/recompute/read path and database history. MockMvc is feasible for much of this class, but adds a distinct full application context to save part of an already small cached class runtime. No worthwhile measured conversion is established in this PR. |
| AdminSitesTest | 1.132 | RETAINED: form sessions, CSRF, org switching, historical attribution and SQL-count assertion with 26 nodes; avoid weakening real persistence/HTTP coverage. |
| AdminMembersTest | 1.241 | RETAINED: real API/form sessions, role/status mutations, timestamp preservation, CSRF and foreign-membership negatives. |
| AdminShellTest | 4.861 | RETAINED full HTTP, SAFE context reuse: cookie-backed form/API sessions, login redirects, expiry, logout and org selection. |
| TenancyAuthTest | 4.612 | RETAINED full HTTP, SAFE context reuse: session/CSRF rotation, expiry, role revocation, inactive membership and tenant negatives. |
| NodeTimeBootstrapTest (#129) | 6.964 | RETAINED: signed proofs, auth/ownership, bounded abuse and a dedicated spy configuration. |
| TimeEvidenceTest (#132) | 4.640 | RETAINED: signer/uncertainty configuration, replay/concurrency, transactional projections and tenant authorization; dedicated two-connection pool and explicit teardown. |
| TemporalProjectionEngineTest (#132) | 0.305 | RETAINED plain JUnit: already tests deterministic intervals without Spring. |

The first full-suite class (AdminCatsTest, 16.713s) pays common cold startup;
it is not evidence that its individual assertions are the dominant bottleneck.
NodeTimeBootstrapTest, production-cost BCrypt checks, real session-expiry waits,
repeated special application startups and DB fixtures remain bottlenecks.
Maximum conversion count is deliberately not the objective.

## Implementation and coverage preservation

The two expiry configurations and probe implementations were identical. Their
class identities nevertheless made Spring cache two different applications.
Both classes now import `SessionExpiryTestConfiguration`; the same controller
runs in one cached real Tomcat/Security/JPA/Flyway context. The probe is stateless
and changes only the requesting session's inactivity interval. CookieManagers
and HttpClients are still recreated before each test. Each fixture still cleans
and reseeds the database; tests remain non-transactional at the test level,
so application transaction commits and repository boundaries are unchanged.

| Modified class | Before -> after | Behavioral assertions before -> after | Retained integration / limitation | Classification |
| --- | --- | --- | --- | --- |
| AdminShellTest | RANDOM_PORT + own imported probe -> RANDOM_PORT + shared imported probe | All 15 test methods, helpers and fixture assertions unchanged: HTML/navigation, redirects, MEMBER/inactive rejection, org switching/foreign-org rejection, form/API credentials, CSRF, logout and real expiry. | Same complete security chain, cookies, Tomcat and real DB. Timing includes cold startup when selected first. | SAFE reuse; RETAINED full-stack |
| TenancyAuthTest | RANDOM_PORT + own imported probe -> RANDOM_PORT + shared imported probe | All 14 test methods, helpers and fixture assertions unchanged: hashing, authentication, session/CSRF rotation, logout invalidation, 12-hour default/real expiry, revocation, pending/disabled state, tenant reads/writes and password rejection/no secret logging. | Same real session/cookie and framework authorization boundaries. Existing sessions in another class are never reused by its fresh clients. | SAFE reuse; RETAINED full-stack |
| BootstrapTest | default/mock full Boot -> same | All eight methods and previous assertions retained; normal-startup test additionally rejects both shared test configuration and probe beans. | Proves ordinary application scanning does not expose the opt-in expiry endpoint. | SAFE strengthened integration |

No tests are deleted, disabled, replaced by Mockito or moved into a weaker
transactional rollback model. No fixtures/password hashes are shared. Original
source from `@BeforeEach` to end-of-file in both HTTP classes was compared against
base main and is identical after line-ending normalization. A first local run
caught duplicate probe registration from a nested component plus a `@Bean`;
using one explicit import fixed it, and the failing class was rerun first.

Framework boundary coverage remains real HTTP: CrossOrgSecurityAcceptanceTest
(A/B/C, identical chip IDs, SYSADMIN/ADMIN/MEMBER, default-deny/scope/nontransitive
sharing, revocation, media/enumeration and history), CookieSecurityTest (production
cookie flags/password default), NodeClaimTest/DeploymentAuthorizationTest
(ownership/claim/history), CatPhotoTest (authenticated media), OrganizationSharingTest
and SharedCareTest. #132 TimeEvidenceTest and #129 NodeTimeBootstrapTest are
untouched; TemporalProjectionEngineTest's 11 pure cases remain untouched.

## Measurements and reproducibility

Environment: Windows 11 Pro, Intel i7-8650U (4 cores/8 threads), approximately
31.9 GiB visible RAM, Temurin OpenJDK 25.0.4.1 (Maven compiles release 21), Python
3.12.10, Spring Boot 4.1.1; unchanged Maven/JVM options and Surefire fork/order.
H2 2.4.240 in PostgreSQL mode is the default test DB. PostgreSQL verification
uses isolated Docker PostgreSQL 18.6 on localhost:55460. CookieSecurityTest,
SystemRoleMigrationTest, SystemRoleMaintenanceTest and DevDemoCommandTest
intentionally use H2 even when TEST_DB_URL selects PostgreSQL for normal tests.

Baseline was measured before source edits. Full and auth baselines had already
compiled output. The initial ingest+visits baseline compiled 120 main/42 test
sources and is **not** used as a comparable throughput benchmark (121 tests,
64.5s including build). Every measurement starts a new Maven process and test
JVM; there is no warmed JVM across runs. OS/dependency caches are warm. Within a
run, Spring caches compatible contexts. Both focused before/after pairs had no
compilation and used the same class order, 29 tests and H2 DB configuration.
Single-class focused runs after edits include compilation/startup and are
verification only, not benchmark evidence.

Commands from repo root:

```powershell
python scripts/test.py backend --domain ingest visits # initial architecture baseline
python scripts/test.py backend                       # full baseline, then once after stabilization
python scripts/test.py backend --domain auth         # before/after affected regression
python scripts/test.py backend --test AdminShellTest
python scripts/test.py backend --test TenancyAuthTest
python scripts/test.py backend --test BootstrapTest
```

For the focused cache experiment only, direct Maven is necessary: the compact
runner intentionally rejects a composite class selector and running two separate
JVMs cannot measure shared context reuse. Run the following twice before and
twice after, using a fresh absolute report directory for every invocation:

```powershell
cd backend
./mvnw.cmd -B -ntp test '-Dtest=AdminShellTest,TenancyAuthTest' '-Dtest.reportsDirectory=<fresh-absolute-directory>'
```

The measurement driver retained complete logs/XML, exact commands, native exit
codes, wall time and class/testcase timings, and explicitly required **29 executed
tests with zero failures**. Context/pool/server counts come from Boot `Started
... in ... seconds`, Hikari `Start completed`, and Tomcat `started on port` logs.
Maven total time includes build/lifecycle overhead. Sum of Surefire class time
includes Spring setup but excludes Maven compilation/lifecycle and some fork
startup/teardown; testcase sums exclude much of Spring class setup. None of
these are presented as isolated CPU time. The parsed per-class measurements are committed in [issue-60-results.json](issue-60-results.json).
Report directories remain under ignored
`test-results/compact/` and `test-results/issue60/` locally.

| Focused pair, two fresh JVMs each | Before runs | After runs |
| --- | --- | --- |
| Invocation wall time (seconds, no compilation) | 31.625 / 32.891 | 29.203 / 34.813 |
| AdminShellTest class seconds (cold first context) | 17.348 / 17.694 | 17.638 / 22.513 |
| TenancyAuthTest class seconds | 4.649 / 5.304 | 3.415 / 2.550 |
| Sum of testcase seconds | 7.326 / 7.976 | 8.139 / 8.356 |
| Boot contexts / JDBC pools / Tomcat servers per run | 2 / 2 / 2 | 1 / 1 / 1 |
| Executed tests per run | 29 | 29 |

Mean sum of Surefire class seconds: 22.498 -> 23.058s (+0.560s, +2.5%);
this also fails to establish an overall execution-time speedup.

Focused invocation mean: 32.258 -> 32.008s, 0.250s (0.8%) lower, with overlapping
ranges. **This does not establish a reliable overall runtime speedup.**
TenancyAuthTest mean: 4.977 -> 2.983s, 1.994s (40.1%) lower when it reuses the
first class's application; the first class's variable cold startup prevents
extrapolating that class improvement to overall throughput. Testcase work did
not decrease. The defensible improvement is **one fewer application, JDBC pool
and HTTP server**, reproducibly 50% fewer for the focused pair.

| Metric | Before | After | Change / confidence |
| --- | ---: | ---: | --- |
| Auth domain invocation wall seconds | 140.5 | 87.9 | 52.6s (37.4%) lower in one run; environmental variance prevents an attributable speedup claim. |
| Auth domain executed tests | 320 | 320 | No reduction. |
| Auth Boot starts / pools / servers | 13 / 13 / 10 | 12 / 12 / 9 | One redundant real application removed. |
| Full H2 backend invocation wall seconds | 106.3 | 110.7 | 4.4s (4.1%) slower in one run; no suite-speedup claim. |
| Full H2 sum of Surefire class seconds | 97.721 | 99.550 | 1.829s (1.9%) slower; excludes Maven compilation/lifecycle. |
| Full H2 sum of testcase seconds | 60.175 | 60.398 | 0.223s (0.4%) higher; no behavioral work removed. |
| Full H2 executed tests | 412 | 412 | No reduction. |
| Full H2 Boot starts / pools / servers | 14 / 14 / 11 | 13 / 13 / 10 | One fewer context/pool (7.1%) and server (9.1%); independent of wall-time noise. |
| Full HTTP test classes | 29 | 29 | All retained; zero converted. |
| Non-server Boot classes | 3 | 3 | Unchanged. |

## Verification and independent review

- PASS: focused AdminShellTest (15), TenancyAuthTest (14), BootstrapTest (8).
- PASS: reverse-alphabetical class order (29), one cached context, zero skips/failures.
- PASS: affected auth regression (320).
- PASS: full H2 suite (412, 110.7s) and full TEST_DB_URL=PostgreSQL 18.6 suite (412, 189.0s). Each covers CrossOrgSecurityAcceptanceTest (8), TimeEvidenceTest (16), TemporalProjectionEngineTest (11), NodeTimeBootstrapTest (13), plus ownership/media/sharing/schema/transaction regressions.
- PostgreSQL was confirmed in Flyway/JDBC/Hibernate logs, not inferred from environment alone. Explicit H2 fixtures remain H2 in that run; no PostgreSQL guarantee was replaced by H2.
- The subsequent review correction restores one original Unicode literal; targeted BootstrapTest verification on both DBs is recorded below. Unchanged full suites are not repeated for this restoration; final CI runs the complete PostgreSQL suite on the final source.
- No application interface changes, so no additional local frontend/API build loop is needed. Existing CI still builds the app and checks API consistency/end-to-end behavior.

Independent reviewer (separate agent/context) inspected the complete implementation,
fixtures, scanning/cache behavior and all four focused XML/log sets. No BLOCKER or
MAJOR findings. One confirmed MINOR: a Windows-default decoding edit accidentally
changed the existing 37-character accented password into mojibake (74 -> 148 UTF-8
bytes). Demonstrated the changed byte count, restored the entire original file from
UTF-8 git bytes and reapplied only the two exclusion assertions. This preserves the
original near-BCrypt-limit regression. PASS: all eight BootstrapTest cases rerun on H2 (38.0s, including recompilation) and PostgreSQL (27.0s). Final scoped re-review:
PASS: reviewer independently verified the final PR diff, restored 37-character/74-byte literal, both eight-case repair runs, full-suite XML/DB evidence and benchmark calculations. No unresolved known in-scope BLOCKER, MAJOR or MINOR remains.

GitHub CI: [PR #147 checks](https://github.com/Grodahn/MiezMerker/pull/147/checks). The final check outcomes are recorded in the PR description after completion; CI retains H2 smoke, the authoritative full PostgreSQL suite, firmware, frontend/API and end-to-end checks. No merge is authorized or performed.

Assessment: local functional/security/temporal/resource checks and independent review PASS; GitHub CI outcomes are linked above and recorded in the PR description. This change reduces initialized application/server/
DB-pool resources while preserving assertions and fidelity. It does not claim
an isolated memory-byte measurement or a statistically reliable suite speedup.
Future layer conversions should first prove a larger attributable gain without
adding enough distinct contexts to offset it.

Efficiency notes: oversized and repeated candidate source reads, a PowerShell brace-glob
syntax error, one main-source path guessed under test sources, and a UTF-8 log
read encountering Windows console encoding were corrected with bounded ranges,
verified paths and replacement decoding for ASCII metrics. Maven runs were
serial; full suites are measured once per phase/database, not after each edit.
