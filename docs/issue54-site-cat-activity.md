# Feeding-site cat activity (#54)

`GET /api/v1/organizations/{organizationId}/feeding-sites/{siteId}/cat-activity`
returns a JSON array of `FeedingSiteCatActivityView` rows. ACTIVE members,
including admins, may read. The server validates membership and site ownership
before querying activity; missing or foreign sites return 404, an inaccessible
organization returns 403, and unauthenticated requests return 401.

| Field | Meaning |
| --- | --- |
| `chipId` | Stored normalized chip ID, including unknown chips |
| `catId` | Current Cat in this organization, or null when unknown |
| `catName` | Current Cat name, nullable even when Cat exists |
| `lastReliableSightingAt` | UTC maximum `end_at` across persisted `visit-gap-v1` visits at this site, or null |
| `lastReceivedAt` | UTC maximum raw `received_at` at this frozen site, or null; server receipt, never sighting time |
| `visitCount` | Number of persisted `visit-gap-v1` visits at this site, zero without visits |

The latest visit's **end**, rather than start, identifies the last reliable
observation within that visit. #10 owns all clock eligibility, gap and visit
semantics. This read endpoint neither aggregates visits nor triggers recompute.
Reliable sightings and counts reflect the last normal recompute; newly ingested
raw chips and receipt metadata are visible immediately. Late data updates visit
information after the normal recompute path runs. Before any reliable visit
exists, `lastReliableSightingAt` is honestly null, even for raw reliable clocks.

UNKNOWN and implausible clocks never become precise sightings. Normal ingest
leaves UNKNOWN observations without frozen site attribution, so they cannot be
included at a particular site. Unattributed observations remain available through
the existing observation API; the current deployment is never used to guess a
site. If stored uncertain rows do have frozen attribution, they contribute only
chip identity and the explicitly separate server receipt metadata.

Both sources filter by organization and their stored `feeding_site_id`. Moving
a Node cannot reinterpret historical activity. Cat metadata is joined using
`(organization_id, chip_id)`, not the optional visit-time Cat snapshot, so later
registration, renaming and deletion are reflected without recomputing visits.
Unknown chips have null Cat metadata; GET never creates Cats. The same chip in
different organizations remains entirely separate.

Ordering is `lastReliableSightingAt DESC NULLS LAST`, then `chipId ASC` for
ties and rows without visits. Pagination follows existing API conventions:
`limit` defaults to 100 (1..1000), `offset` defaults to 0 (nonnegative).
Invalid pagination returns 400. An empty site or exhausted page returns `[]`.
As with other offset APIs, concurrent ingest/recompute can change page contents.

A single SQL query groups visits and raw receipt metadata, unions chip identities,
joins current Cat metadata and orders/limits the result in the database. Composite
indexes scope both aggregates by organization and frozen site. No histories or
entities are materialized per chip; query count is independent of result size.
The query is integration-tested on H2 in PostgreSQL mode and PostgreSQL.

The final FeedingSite PWA page, navigation changes, bowl naming/management and
provisioning remain deferred to their own tickets. The generated TypeScript
client exposes this endpoint for later UI work.
