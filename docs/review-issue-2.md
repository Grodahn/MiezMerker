# Review of PR #20 against issue #2

Reviewed on 2026-10-02: architecture/ADRs and all acceptance criteria, portable
core/platform/simulator boundaries, BLE schema/fixture, PWA offline storage and
browser ports, HTTP security/DTOs, OpenAPI generation and hardware-free CI.
Feature implementations deliberately deferred by #2 are not defects in this review.

## Fixed findings

- **P2 — Pending data could be overwritten.** `DexieOutbox.put` used IndexedDB
  upsert for a globally keyed batch ID. Reusing that ID with another payload or
  Organization silently replaced queued data. Both sequential and concurrent
  conflict regressions failed against the original implementation. The adapter
  now checks existing envelope metadata and bytes in one read/write transaction;
  exact retries are idempotent and conflicts reject without overwriting data.
- **P2 — Version endpoint could report the wrong build.** The response repeated
  a hardcoded Maven release string. A version bump would leave the API reporting
  the old version. Spring Boot build-info now supplies BuildProperties to the
  endpoint. A regression test injects a different release and verifies that value.

Verification: PWA suite now includes 7 tests (durability, retries, conflicts,
concurrency, shared BLE fixture, collector isolation and generated client).
Backend suite now includes 3 tests, including the build metadata regression.
OpenAPI schemas are unchanged. CI additionally checks PostgreSQL, packaged backend,
offline browser integration and core/simulator builds before merge.

No further confirmed finding within issue #2 scope remained after these fixes.
