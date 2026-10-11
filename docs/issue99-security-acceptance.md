# Security acceptance A/B/C isolation and revocation (#99)

Epic #89, ADR 0016. This is a **security acceptance record**, not a feature
specification. The implementation comes from #90–#98; #99 verifies it with a
deterministic three-organization fixture and focused negative tests.

## Fixture (CrossOrgSecurityAcceptanceTest)

Organizations A, B, C on one self-hosted backend/database, each with an
ACTIVE ADMIN and an ACTIVE MEMBER, an organization-local Cat for the same
chip `987654321098765` with different names/notes/photos (`Minka-A`,
`Schwarzi-B`, `Tiger-C`; red/blue/green JPEGs), CLAIMED nodes, feeding sites
with deployment history, SYNCED plus UNKNOWN observations and recomputed
visits. SYSADMIN (no membership) and a stranger (no membership) are included.
Different sharing policies per test. All assertions use the real backend
authorization layer over HTTP or through the authorized services; no mocks
confer access.

Reproduce (new in this PR):

```text
python scripts/test.py backend --test CrossOrgSecurityAcceptanceTest
python scripts/test.py frontend src/management/shared-presenter.test.ts
```

Pre-existing browser coverage (unchanged by this PR, run in CI):

```text
cd app
npm run test:e2e -- e2e/shared-care.spec.ts
```

## Roles and access boundaries — PASS

- Global SYSADMIN administers organizations but reads no Cats, photos,
  observations or visits without a local ACTIVE membership (403).
- Organization ADMIN manages only its own organization (grants, deletes,
  visibility); cross-organization administration is 403.
- Organization MEMBER reads/writes own-organization data but cannot grant
  policies, delete Cats or recompute other tenants' data (403/404).
- Disabled users, disabled memberships, disabled organizations and logged-out
  sessions are rejected (401/403); existing sessions do not survive
  disablement or logout.

## A/B/C scenarios — PASS

- Without grants, A lists only `Minka-A`; B's and C's Cats, observations,
  visits, site names, photos and counts are undiscoverable through Cat
  lists/details, observation lists, visit lists, `latest-per-site`,
  chip-activity, shared-care resolve/visits/photo, photo metadata/binary,
  directory or admin endpoints. Same chip grants no visibility.
- Hidden owners are indistinguishable from ungranted ones (empty resolve,
  empty visit pages, uniform 404 media).
- Error bodies disclose no foreign names, notes, coordinates or private
  contacts. The default Spring error echoes the requested path only, which
  the requester already supplied.

## Sharing-scope matrix — PASS

| Grant | Effect |
| --- | --- |
| CARE | Foreign display name plus provenance only; no visits, photos or site names |
| CARE + VISITS | Visit summaries; site names stay null without SITE_LABEL across items, ordering and pagination |
| CARE + VISITS + SITE_LABEL | Display names added; coordinates, addresses, descriptions, notes never shared |
| CARE + PHOTO | JPEG delivery with observation proof; `no-store`, `nosniff` |
| VISITS without CARE | No effective access (prerequisite enforced at resolution) |
| PHOTO without CARE | No effective access |
| SITE_LABEL without VISITS | No effective access |
| Grant to C only | A sees nothing |

Forged or foreign observation references fail uniformly with 400
(rate-limited) before any foreign discovery; missing grants fail with empty
pages or uniform 404. Guessed Cat ids are not observation proof.

## Revocation behavior — PASS

Scope revocation, recipient removal, owner/recipient hiding, organization
deactivation (SYSADMIN surface), membership disablement and logout each block
the next backend read. Hidden/disabled grants never silently reactivate:
hiding deletes outgoing grants and allowlist entries; deactivation
invalidates through the same path; reactivation requires fresh grants.
Organization A → B → A switching keeps each tenant view separate.

Known limitation: previously downloaded copies on another user's device
cannot be remotely erased; the UI communicates this and never restores
foreign data from obsolete responses.

## Photo-access behavior — PASS

- Same chip keeps independent, distinguishable bytes per organization;
  replacement in A never touches B.
- Local upload/read/replace/delete require ACTIVE membership of the owning
  organization; foreign tenants get 403 (member-gated paths) or 404
  (identifier-gated paths, uniform with unknown ids).
- Foreign delivery requires effective CARE + PHOTO plus genuine own
  observation proof; CARE alone, forged proof, wrong requesting organization
  and revoked grants all deny without enumerating media.
- Bytes carry `Cache-Control: no-store` (+ `nosniff`, restrictive CSP);
  presence metadata (`hasPhoto`) covers only the caller's own organization.
- SYSADMIN without membership is denied on all media paths.

## Observation-backed authorization — PASS

Recipient-owned, persisted observations from CLAIMED nodes are the only
proof; manually created Cats, guessed chips and free-form chip parameters
are rejected. UNKNOWN-clock observations prove a chip but never supply visit
times. Pagination re-checks grants per page; cursors/offsets are never
authority. Bounded batches and per-call rate limits prevent enumeration.

## Cache and offline boundaries — PASS

Shared-care and photo responses carry `no-store`; the presenter writes only
transient DOM and blob URLs, never Dexie/IndexedDB/Web Storage/Cache
Storage. Reload, org switch, logout, expired sessions, offline, blur,
pagehide, navigation and Admin returns clear foreign DOM and revoke blob
URLs; late responses never restore data after a context change
(`shared-presenter.test.ts`, `e2e/shared-care.spec.ts`).

## Negative API tests — PASS

Covered in `CrossOrgSecurityAcceptanceTest` (8 tests): default-deny across
all surfaces, scope matrix, nontransitivity, revocation/lifecycle, photo
security, role/lifecycle boundaries, historical-data integrity and
member-delete scoping. Existing suites (`OrganizationSharingTest`,
`SharedCareTest`, `CatPhotoTest`, `TenancyAuthTest`,
`AdminOrganizationsTest`, `AdminOrganizationSettingsTest`) cover policy
validation, audit, CSRF, provisioning-hint privacy and concurrent
replace/delete consistency.

## Known limitations

- Previously delivered copies cannot be recalled from foreign devices
  (documented, by design).
- No pushed revocation event: a continuously visible idle page learns about
  revocation at its next request; no stale projection is reused as fallback.
- Timing side channels are out of scope beyond bounded, rate-limited,
  constant-shape responses; no complete-anonymity claim for radio/device
  advertisements (#18 compatibility gate, ADR 0016).
- Federation between separate servers is out of scope (single-instance epic).

## Assessment

- Isolation, role boundaries, explicit sharing, revocation,
  observation-backed access and secure photo delivery: **PASS** (tested and
  verified as above).
- FAIL: none confirmed in scope.
- UNVERIFIED: PostgreSQL and full end-to-end runs beyond CI; physical
  BLE/node behavior (unchanged by this issue).
- OUT OF SCOPE: timestamp reconstruction (#126–#134), media-storage
  redesign, federation, legal/GDPR determinations.

Epic #89 is ready to close once this PR's CI is green and reviewers accept
the evidence above; do not close it automatically from this issue.
