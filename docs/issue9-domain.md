# Domain relationships (Issue #9)

This document describes the organization-scoped backend domain introduced by issue #9,
its relationships, constraints and known limitations.

## Entity relationship overview

```
Organization
├── FeedingSite (1:N)
├── Node (1:N, claimed)
├── Cat (1:N)
├── NodeDeployment (1:N, links Node + FeedingSite with validity range)
└── RawObservation (1:N, linked to Node; frozen FeedingSite/Deployment at ingest)
```

All business entities carry `organization_id` directly or are unambiguously
reachable through an organization-owned parent. Server-side authorization always
derives the active organization from the authenticated user's ACTIVE membership;
a client-supplied `organization_id` is never trusted.

## FeedingSite

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| organization_id | UUID | FK → organizations, NOT NULL |
| name | VARCHAR(255) | NOT NULL |
| description | VARCHAR(2000) | |
| location_lat | DOUBLE PRECISION | |
| location_lng | DOUBLE PRECISION | |
| location_label | VARCHAR(255) | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

- Names are not unique: several sites of one organization may share a name; there is
  no DB unique constraint on `(organization_id, name)`
- CRUD: read=ACTIVE, write=ADMIN
- Location coordinates validated to ±90/±180; must be set together or both null

## Cat

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| organization_id | UUID | FK → organizations, NOT NULL |
| chip_id | VARCHAR(64) | NOT NULL, normalized (trim + uppercase) |
| name | VARCHAR(255) | |
| status | VARCHAR(64) | |
| notes | VARCHAR(2000) | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

- `UNIQUE (organization_id, chip_id)` — the same chip ID may exist independently
  in two organizations without cross-tenant access.
- CRUD: read=ACTIVE, write=ADMIN

## Node (extended from #18)

| Field | Type | Notes |
|-------|------|-------|
| node_id | UUID | PK, random UUIDv4 from device |
| organization_id | UUID | FK → organizations, NULL while UNCLAIMED |
| public_key_x/y | VARCHAR(64) | P-256 device public key |
| fingerprint | VARCHAR(64) | UNIQUE, device key fingerprint |
| state | VARCHAR(16) | UNCLAIMED / CLAIMED |
| firmware_version | VARCHAR(64) | |
| protocol_version | VARCHAR(32) | |
| status_note | VARCHAR(500) | |
| last_contact_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |
| claimed_at | TIMESTAMPTZ | |

- Claiming (#18) assigns organization; Factory Reset rotates identity (new UUID + key)
- Management: read=ACTIVE, update=ADMIN (metadata only)
- Public `/owner` endpoint exposes organization slug/name/contact for claimed nodes

## NodeDeployment

Historical assignment of a Node to a FeedingSite with a validity interval.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| organization_id | UUID | FK → organizations, NOT NULL |
| node_id | UUID | FK → nodes, NOT NULL |
| feeding_site_id | UUID | FK → feeding_sites, NOT NULL |
| valid_from | TIMESTAMPTZ | NOT NULL, inclusive |
| valid_until | TIMESTAMPTZ | NULL = currently open, exclusive |
| created_at | TIMESTAMPTZ | |

- Intervals for one node must not overlap; enforced by serializing deployment writes
  on the node row (pessimistic lock) and a `CHECK (valid_until IS NULL OR valid_until > valid_from)`.
- A move closes the old interval and opens a new one.
- CRUD: read=ACTIVE, write=ADMIN
- Deleting a deployment keeps frozen observation attributions intact.

## RawObservation

Immutable primary data ingested from a Node. Never rewritten.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| organization_id | UUID | FK → organizations, NOT NULL |
| node_id | UUID | FK → nodes, NOT NULL |
| sequence | BIGINT | NOT NULL, ≥1, per-node monotonically increasing |
| chip_id | VARCHAR(64) | NOT NULL, normalized (trim + uppercase) |
| observed_at_ms | BIGINT | NULL exactly when `clock_status = UNKNOWN` |
| clock_status | VARCHAR(16) | SYNCED / RTC_ONLY / UNKNOWN / KNOWN (KNOWN is a stored wire alias, see below) |
| incarnation | VARCHAR(36) | Device reset epoch (UUID string) |
| monotonic_ms | BIGINT | Device monotonic clock at read time |
| boot_counter | INTEGER | Boot epoch |
| feeding_site_id | UUID | FK → feeding_sites, NULL if unresolved |
| deployment_id | UUID | FK → node_deployments, NULL if unresolved |
| received_at | TIMESTAMPTZ | Server receipt time, NOT NULL |

- **Unique constraint**: `(node_id, sequence)` — idempotency key at DB level.
- `observed_at_ms` is the raw RTC value from the node at read time; it is never
  corrected or normalized after ingestion. Corrected time belongs to #10 as
  separate derived information.
- `clock_status = UNKNOWN` means no trustworthy wall-clock value exists; the
  observation is stored without `feeding_site_id` / `deployment_id` rather than
  guessing the current site.
- Idempotent ingest: identical retry accepted; same key with different payload
  rejected (conflict), original kept.
- Unknown nodes rejected (claim via #18 first).
- Filters: organization (mandatory), node, feeding site, chip, time range
  (`observed_at_ms` only matches rows with a timestamp).

## Ingest behavior summary

| Scenario | Result |
|----------|--------|
| First occurrence | `CREATED` |
| Identical retry | `DUPLICATE_IDENTICAL` |
| Same key, different payload | `CONFLICT` (logged, original kept) |
| Unknown node | `UNKNOWN_NODE` (403-level) |
| Invalid chip/sequence/timestamp | `INVALID` (400-level) |
| Foreign-organization node in batch | `FORBIDDEN` (per-item, sibling items still commit) |

## Known limitations

1. **Clock UNKNOWN + deployment attribution**: Observations with `UNKNOWN`
   clock status carry no wall-clock value and are stored without
   `feeding_site_id` / `deployment_id`. They are never attributed to the
   node's current site. Sequence-based boundary resolution for such rows is
   deferred to #10.

2. **Deployment overlap prevention**: Overlap freedom is enforced by
   application-level serialization on the node row. Concurrent ADMIN calls to
   create/close deployments for the same node are serialized; no database
   exclusion constraint exists on `(node_id, valid_from, valid_until)` because
   PostgreSQL lacks built-in range exclusion without `btree_gist`/`btree_gin`
   and the business rule is adequately enforced by the pessimistic lock.

3. **No PUT on observations**: Raw observations are immutable. No update
   endpoint exists. PUT requests return 403/405.

4. **Chip ID normalization**: Trimming + uppercasing is applied at ingest and
   in Cat CRUD. The BLE protocol (`raw-observation-v1.schema.json`) defines
   `chip_id` as a non-empty string; normalization happens on the backend to
   ensure consistency with the Cat table.

5. **Clock status alias**: The BLE protocol view (`protocol_view`) collapses
   `SYNCED`/`RTC_ONLY` to lowercase `known` and maps `UNKNOWN` to `unknown`.
   Backend accepts `KNOWN` (any case) on ingest and stores it verbatim as a
   fourth `clock_status` value. Like `SYNCED`/`RTC_ONLY`, `KNOWN` requires
   `observed_at_ms` and participates in deployment attribution; only `UNKNOWN`
   rows stay unattributed. The value is returned as stored (`KNOWN`), never
   silently rewritten to `SYNCED`.

## API summary (OpenAPI v1)

| Endpoint | Methods | Auth | Notes |
|----------|---------|------|-------|
| `/api/v1/organizations/{orgId}/feeding-sites` | GET, POST | ACTIVE / ADMIN | |
| `/api/v1/organizations/{orgId}/feeding-sites/{siteId}` | GET, PATCH, DELETE | ACTIVE / ADMIN | |
| `/api/v1/organizations/{orgId}/cats` | GET, POST | ACTIVE / ADMIN | |
| `/api/v1/organizations/{orgId}/cats/{catId}` | GET, PATCH, DELETE | ACTIVE / ADMIN | |
| `/api/v1/organizations/{orgId}/deployments` | GET, POST | ACTIVE / ADMIN | |
| `/api/v1/organizations/{orgId}/deployments/{deploymentId}` | GET, PATCH, DELETE | ACTIVE / ADMIN | |
| `/api/v1/observations/ingest` | POST | ACTIVE | Idempotent batch |
| `/api/v1/observations` | GET | ACTIVE | Filter: org, node, site, chip, time |
| `/api/v1/observations/{observationId}` | GET | ACTIVE | Tenant-gated |
| `/api/v1/organizations/{orgId}/nodes/{nodeId}/observations` | GET | ACTIVE | Explicit org+node scope |
| `/api/v1/nodes` | GET | ACTIVE | |
| `/api/v1/nodes/claim` | POST | ADMIN | #18 |
| `/api/v1/nodes/{nodeId}` | GET, PATCH | ACTIVE / ADMIN | |
| `/api/v1/nodes/{nodeId}/owner` | GET | public | Claimed nodes only |

## API versioning and error format

- Versioning: all endpoints live under `/api/v1`; the OpenAPI document reports
  `version: v1`. Breaking changes require a version/migration decision (see
  `docs/architecture.md`).
- HTTP errors use Spring Boot RFC 7807 problem details
  (`application/problem+json`): `status`, `title`, `detail`, `instance`.
  Authentication failures return `401`, missing/inactive membership `403`,
  unknown ids `404` (foreign-tenant ids also return `403`/`404` and never
  data), validation failures `400`, deployment overlap / duplicate chip
  `409`, oversized batch `413`.
- Batch ingest (`POST /api/v1/observations/ingest`) always returns `200` with
  per-item results (`CREATED`, `DUPLICATE_IDENTICAL`, `CONFLICT`,
  `UNKNOWN_NODE`, `INVALID`, `FORBIDDEN`); only malformed batches
  (missing org, null entries, >5000 items) fail the whole request.

## Test coverage

The integration test suite `Issue9IngestTest` covers:

- Empty batch, single observation, large batch, duplicate batch
- Partially existing batch, conflicting payload
- Parallel duplicate uploads (concurrent safety)
- Unknown node, invalid chip/timestamp/clock status
- Cross-tenant isolation (A cannot read B's sites/cats/obs/nodes)
- Cross-tenant ingest (A cannot ingest for B's node)
- Same chip ID in two organizations stays independent
- Node move preserves historical attribution
- Deployment boundary cases (inclusive `valid_from`, exclusive `valid_until`)
- UNKNOWN clock observations stored without attribution
- Raw timestamp/clock status immutability
- Member vs ADMIN role boundaries
- Node metadata management tenant-scoped
- Deployment requires claimed node of same organization
- Combined filters (organization + node + chip + time)

All tests run on H2 (default) and PostgreSQL 18.6 (CI).

## OpenAPI contract

The authoritative OpenAPI spec is exported from the running backend
to `backend/target/openapi.json`, canonicalized to
`backend/openapi/v1.json`, and the TypeScript client is generated to
`app/src/api/generated.ts`. CI verifies both committed files byte-for-byte
and repeats the check. Never hand-edit frontend DTOs.