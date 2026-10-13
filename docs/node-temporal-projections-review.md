# Issue #132 independent code, architecture and security review

Date: 2026-10-13. A separate reviewer agent/context examined the actual entities,
migration, services, API, tests, ADR 0017, finalized #128/#129 contracts, and the
complete implementation scope. The reviewer did not author implementation changes
or run concurrent Maven tests. This is an independent agent review, not external
security certification or hardware validation.

## Confirmed findings and repairs

| Severity | Finding | Repair and regression |
| --- | --- | --- |
| MAJOR | Intermediate boots originally returned identical outer bounds, ignoring their mandatory internal duration and allowing a boot span longer than the outer window. | Carry first/last monotonic constraints with conservative Node drift and pairwise sampling error; `intermediateBootSpanCannotFitShortOuterWindow`. |
| MAJOR | The first repair checked intermediate boots independently, allowing two 4000 ms boot spans inside a roughly 5000 ms outer window. | Forward/backward timeline constraints accumulate every intervening boot's minimum powered-on duration, never shutdown time; `consecutiveUnanchoredBootDurationsAccumulateAndDetectImpossibleOuterWindow`. |
| MAJOR | Repeated foreign-boot propagation was quadratic per observation and could cause extremely large work under the Node lock. | Precompute boot constraints once, then propagate events from those summaries. Bound collections at 4096 and each boot at 64 anchors; representative 4096-anchor/100-projection and capacity regression. |
| MINOR | Initial transaction/retry coverage did not reproduce ingest interruption and valid conflicting acceptance under one stable anchor ID. | Real TransactionTemplate interruption rolls back newly inserted boot and anchor; subsequent retry creates them once. Device-signed changed-acceptance retry returns 409 and retains original. Separate valid nonce-replay regression. |
| MINOR | Relative durations needed explicit pairwise sampling uncertainty. | Persist server/sampling components separately, include twice the largest justified sampling budget in minimum durations; schema keeps budget fields all-or-none. |
| MINOR | Generated API types did not represent the null endpoints/cursor actually serialized for UNKNOWN/CONFLICT and final pages. | Mark every nullable projection/cursor field explicitly nullable, regenerate OpenAPI/TypeScript, and assert both real UNKNOWN HTTP nulls and nullable schema. |

The independent reviewer rechecked the corrected algorithms, immutable schema,
authorization, proof binding, and regressions. No unresolved confirmed in-scope
BLOCKER or MAJOR remains in the reviewed source. Final PR artifact and CI closeout
are recorded in the PR description.

## Integrated review observations

- Exact #129 verifier reuse: pinned backend issuer/key/purpose, registered Node key,
  authenticated challenge uptime, nonce/identity/boot and exact compact proof digest.
  Presented self-verifying keys cannot establish ownership.
- Owner-only ACTIVE tenant checks and generic Node denials; global SYSADMIN and
  sharing do not grant private evidence/projection access.
- Pre-claim evidence fails closed without an independently provable continuity
  chain. Old key/claim contexts cannot silently authorize old evidence.
- Stable scoped IDs, exact bytes, nonce/proof uniqueness, database locks and
  transactional boot/anchor writes support idempotency without overwrite.
- Checked BigInteger/signed millisecond arithmetic; conservative asymmetric
  acceptance bounds, drift and sampling; no midpoint or raw time promotion.
- Full-lifetime chronology validation includes pagination boundaries. Multiple
  boot constraints never subtract unrelated uptime or invent powered-off duration.
- Historical interval containment and overlap checks preserve frozen raw
  attribution. No new DerivedVisit algorithm, BLE/PWA transport, firmware,
  unfinished #130 dependency or destructive migration.
- Bounded collection loading, fetched historical site references, cursor indexes
  and representative synthetic volume regression. Lifetime SQL scans remain
  documented; no production throughput claim.

## Verification

Initial PostgreSQL CI exhausted database clients when the new dedicated
signer/uncertainty test context retained its Hikari pool. Mark that nonreusable
test context dirty after its class to close the pool before later test contexts,
and limit its dedicated pool to two connections (the real concurrent-ingest test
still uses both). The previously green base CI confirms this new retained pool
was the additional resource that exceeded PostgreSQL's client limit.
This changes test-resource lifecycle only; production pool configuration and
test expectations/selection remain unchanged. Final CI status is in the PR.

Local completion: node/auth/schema domain union **333 tests PASS**.
Focused interval/reconstruction regression: **11 tests PASS**.
Focused evidence/service/HTTP/security class: **16 tests PASS**.
Existing #129 golden-vector negative cryptographic tests ran in the domain union.
Generated OpenAPI/TypeScript consistency and frontend build are verified separately
and reported alongside exact GitHub CI status in the PR.

Physical oscillator calibration, live server-clock monitoring and Android/ESP32
acceptance are **UNVERIFIED**. Configured budgets must refer to justified operational
evidence; synthetic test budgets are not physical calibration. Missing budgets
retain verified installation bytes but no certified UTC.

