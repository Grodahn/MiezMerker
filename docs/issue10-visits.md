# Derived visits (Issue #10, `visit-gap-v1`)

This document is the implementation contract for backend-side visit
aggregation. Issue #10 is the sole contract; firmware and PWA never
aggregate visits.

## Algorithm `visit-gap-v1`

Deterministic, reproducible, backend-only grouping of immutable
`RawObservation`s.

- Grouping key: `(organization_id, feeding_site_id, chip_id)`.
  `feeding_site_id` is the **frozen** value stored on each raw observation at
  ingest time (historical `NodeDeployment` attribution from #9). The node's
  current site is never consulted.
- Usable observations only:
  - `clock_status` in `SYNCED`, `RTC_ONLY`, `KNOWN`,
  - `observed_at_ms` non-null, `> 0` and `< 9224318016000000`
    (PostgreSQL finite upper bound, same as ingest validation),
  - frozen `feeding_site_id` non-null.
- Ordering inside a group:
  `(observed_at_ms ASC, node_id ASC, sequence ASC, id ASC)`.
  Ingest/`received_at` order never affects the result. Equal timestamps are
  broken deterministically by stable identifiers.
- Gap rule (explicit boundary): consecutive observations belong to the same
  visit while `gap <= threshold` (inclusive). A gap of exactly the threshold
  stays together; `threshold + 1 ms` splits. Covered by automated tests.
- Default threshold: **60 seconds**. Configurable via
  `miezmerker.visits.default-gap-seconds` (env `VISITS_DEFAULT_GAP_SECONDS`,
  default `60`) and per recompute request (`gapSeconds`, `1..86400`). The
  value is a hypothesis/default, never a hardcoded business rule. The used
  value is stored on every `DerivedVisit` (`gap_seconds`).
- Visit fields: `organization`, `feeding_site`, `chip_id`, optional `cat`
  snapshot, `start_at`/`end_at` (from first/last `observed_at_ms`),
  `observation_count`, `algorithm_version`, `gap_seconds`,
  `first_observation_id`/`last_observation_id` provenance, `created_at`.
- `algorithm_version` is stored on every visit. Only `visit-gap-v1` exists;
  a new version can re-evaluate historical raw data side-by-side
  (recompute is scoped to `(organization, algorithm_version)`).

## Clock correctness

- Trustworthy clocks (`SYNCED`, `RTC_ONLY`, `KNOWN`) use the documented raw
  `observed_at_ms` directly.
- `UNKNOWN`, null, implausible (`<= 0` or `>= MAX`) or otherwise untrustworthy
  timestamps: no elapsed time is invented, `sequence`/`monotonic_ms` are never
  used as duration, and such rows are never silently assigned to a visit.
- Safe default: excluded from time-based aggregation. They remain stored
  unchanged and listable via `GET /api/v1/observations`; the recompute
  response reports `excludedUnknownClock`, `excludedImplausibleTime` and
  `excludedUnattributed` counts for auditability.
- No corrected/normalized timestamps exist in `visit-gap-v1`. If introduced
  later, they must be separate derived data with provenance/versioning; raw
  timestamps are never overwritten (see #9 immutability).

## Deployment + bad-clock interaction

- `UNKNOWN` rows are stored at ingest without `feeding_site_id`/
  `deployment_id` (see `docs/issue9-domain.md`) rather than guessing the
  current site. Aggregation likewise excludes every row with null frozen
  site, including known-clock rows whose timestamp falls outside any
  deployment interval.
- Recompute uses only frozen attribution, never a live deployment lookup.
  Moving a node later cannot reinterpret old observations or visits.
- Known limitation (no hidden rewrite): an observation ingested before any
  covering deployment exists stays unattributed (`NULL`) even if a
  retroactive deployment is created later covering its timestamp. It remains
  excluded until an explicit versioned re-attribution exists. If that
  re-attribution is wanted, file it as a follow-up issue; `visit-gap-v1`
  intentionally does not fabricate history.

## Tenant isolation

Every visit query and recompute filters by `organization_id` and goes through
`TenantService`: listing requires `ACTIVE` membership, recompute requires
`ADMIN`. A client-supplied `organization_id` alone never grants access.
The same `chip_id` in two organizations yields fully independent visit sets.

## Determinism / rebuild

- Same `(raw dataset, deployment history via frozen attribution, algorithm
  version, gap configuration)` always yields the same visit business result
  (site, chip, start, end, count, first/last observation IDs). Surrogate
  visit UUIDs are regenerated on rebuild; business fields are stable.
- Recompute: `POST
  /api/v1/organizations/{orgId}/visits/recompute` (`ADMIN`,
  `{gapSeconds?, algorithmVersion?}`). Deletes only derived rows of
  `(org, algorithm)` and inserts the new result in one transaction. No raw
  re-ingest, raw rows unchanged, failures roll back without partial derived
  state.
- Late data: ingest the late `RawObservation` normally (idempotent
  `(node_id, sequence)`), then recompute. The late row is ordered by its
  `observed_at_ms`, correctly merging/splitting visits.
- Duplicate raw rows cannot duplicate visits: ingest deduplicates by
  `(node_id, sequence)` and recompute reads the stored set once.

## API (OpenAPI v1)

| Endpoint | Auth | Notes |
|----------|------|-------|
| `GET /api/v1/organizations/{orgId}/visits` | ACTIVE | Filters `feedingSiteId`, `chipId`, `fromMillis`/`toMillis` (on `start_at`), `limit`/`offset`; deterministic order |
| `GET /api/v1/organizations/{orgId}/visits/{visitId}` | ACTIVE | Tenant-gated; foreign ids are `404` |
| `POST /api/v1/organizations/{orgId}/visits/recompute` | ADMIN | Body `{gapSeconds?, algorithmVersion?}`; returns counts + visits |

`GET /api/v1/observations*` continues to expose raw data separately.

## Tests

`Issue10VisitsTest` covers: issue example, table-driven `5/30/60/90/120 s`,
inclusive boundary, alternating cats, multi-site, cross-org independence,
node move, late arrival, `UNKNOWN`/unattributed exclusion, long/single
sequences, idempotency, ingest-order independence, tenant negatives,
member/admin roles, invalid gap/algorithm (no partial state), default gap,
cat snapshot, deployment inclusive/exclusive bounds, equal-timestamp
determinism.
