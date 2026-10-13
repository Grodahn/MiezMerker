# Interval visit history (#133)

ADR 0017 and #132 remain authoritative. `visit-gap-v1`, persisted DerivedVisit
rows, raw payloads and historical attribution are unchanged. The new independent
read model is `visit-interval-v1`, with `boot-interval-v1` time provenance and a
fixed inclusive 60-second gap. It never writes interval endpoints into exact
DerivedVisit start/end fields, and never subtracts receipt times or cross-boot
uptime values.

## API and reproducibility

`GET /api/v1/organizations/{organizationId}/visits/temporal?chipId=...&offset=0&limit=20`
requires ACTIVE owner membership, returns no-store, and limits pages to 1..100.
Aggregation precedes pagination. Technical order is node/incarnation/boot/sequence,
not guessed UTC or server receipt. `nextOffset` comes from the grouped result,
including unknown records. Observation IDs make item IDs deterministic.

The entire selected chip snapshot is projected through #132 under contributing
Node locks acquired in deterministic order. A concurrent first read on another
Node produces 409/retry rather than a partially locked result. Identical raw,
anchor, deployment and registered-key snapshots produce identical results.
Late immutable evidence deliberately changes the current projection; a new page
request rebuilds it, just as #132 does. Consumers should refresh pagination after
new uploads. Algorithm version, original observation IDs, node/incarnation/boot,
sequence/uptime, evidence IDs, policy provenance and historical deployment IDs
are returned so the calculation can be audited. Sequence, uptime and UTC integer
fields in this new response are decimal strings, preserving JavaScript precision.

There is no separately stale interval materialization or destructive recompute:
reading deterministically recomputes the projection. The existing v1 recompute
endpoint remains unchanged. No schema migration is needed. Existing composite
organization/chip and temporal indexes support scoped reads. The new endpoint
fails closed at 4096 observations per selected chip or contributing Nodes;
#132 retains its existing lifetime evidence/deployment limits. Excess returns
503 rather than omitting records or certifying an incomplete history. Streaming
large histories is a future scaling requirement, not a silent truncation policy.

## Visit decisions

Only RECONSTRUCTED/VERIFIED closed intervals with confirmed persisted historical
site attribution can become visits. VERIFIED remains reserved by #132; original
SYNCED/RTC_ONLY alone never gains this quality. A singleton eligible read is a
visit with an explicit window, not an exact time/duration. BOUNDED, UNKNOWN,
ESTIMATED, CONFLICT, open intervals and missing attribution stay observations.
Frozen raw attribution is preserved; derived attribution uses #132's entire
interval containment in exactly one historical deployment. Current assignment
is never consulted.

For consecutive eligible reads A and B from the same Node, incarnation, boot,
chip and site, conservative independent bounds give:

- smallest possible gap: B.earliest - A.latest
- largest possible gap: B.latest - A.earliest

Merge only when the smallest gap is nonnegative and the largest is <=60000 ms.
An overlap or threshold-straddling gap marks both reads ambiguous; neither is
promoted to a guaranteed visit. Ambiguity propagates through every proven-merge
neighbor so a connected read cannot be falsely split off into its own visit.
A proven gap >60000 ms stays separate. Cross-boot/cross-node merging is
conservatively disabled. This version does not model correlated oscillator
errors to shrink bounds. Original per-read windows remain available within a
merged visit, and its outer window is not displayed as a duration.

## UI and privacy

Cat detail in the PWA has a separate interval/observation history alongside v1
history. Admin cat/visit pages link to `/admin/visits/temporal`, with chip search
and pagination. Both show human-readable quality, earliest/latest supported
boundaries, uncertain visit membership and unconfirmed sites, plus original
relative order/provenance in accessible details. PWA formats UTC in the user's
local timezone; Admin progressively localizes server-rendered UTC using an
external script and preserves UTC with JavaScript disabled. No graphics were
added; existing assets and navigation remain unchanged. Responsive cards wrap
long identities and provenance at mobile widths.

The new detailed projection is owner-only. Shared-care grants, including VISITS,
do not authorize raw IDs, anchors, counts or temporal metadata through this
endpoint. Existing narrow shared-care visit summaries and CARE/VISITS/SITE_LABEL
gates are unchanged. No foreign temporal projections are fetched or stored
by this feature. PWA requests use no-store and existing abort/context handling;
no IndexedDB, outbox, BLE or evidence synchronization changes were made.

## Verification boundary

Deterministic synthetic backend fixtures verify reconstruction, multiple boots,
bounded/unknown/conflicting data, conservative gap boundaries, immutable retries,
historical/missing attribution, tenant/chip isolation and pagination. Existing
v1 regressions verify compatibility; shared-care tests verify unchanged grants
and denial of detailed foreign history. Frontend tests cover accessible labels,
local timezone formatting, unknown placeholders and responsive presentation.
The uncertainty budgets are synthetic fixtures, not measured hardware accuracy.
Capacity regression verifies 503 without data loss; both interfaces show an
accessible limitation message rather than claiming an empty history.
#131 only supplies future authenticated evidence through the existing #132
interface; this PR does not depend on its merge. Android/ESP32, evidence delivery,
physical drift calibration and full power-loss E2E remain #134 verification.

## Integrated review and applied fixes

- MAJOR: ambiguity now propagates across proven-merge neighbors, preventing
  unsupported separate visits next to an uncertain boundary. Focused regression
  covers the complete connected chain.
- MAJOR: new API uses lossless decimal strings for sequence/uptime/UTC, with
  matching generated OpenAPI types. Existing #132 transport fields are unchanged.
- MINOR: UTC bounds retain millisecond precision in localized Admin/PWA labels;
  truncating the upper bound would misleadingly narrow the interval.
- MINOR: new Admin localization script is explicitly proxied to Spring in both
  development and preview; the existing session-gate test covers this resource.
- MINOR: contributing-node locking replaces organization-wide locking; a new-node
  snapshot race fails with 409/retry before any partially protected projection.

No trust arithmetic or security-authority changes were made. Review checked the
actual changed services/controllers, template/script integration, generated API,
legacy and sharing contracts, and related tests.

Final local verification passed: visit-domain regression (122 tests), repaired
aggregation (7), final Admin interval HTTP fixture (1), capacity fixture (1),
Admin regression (7), API export (2), management regression (69), final temporal
presentation (4), production build/API consistency, and Chromium layouts at
320/430px (2). Counts describe individual selections and overlap; they must not
be added as unique coverage. No Android/ESP32 testing was performed.
