# Shared-care backend reads (#97)

ADR 0016 and the outgoing policies from #96 govern these online-only projections.
Both routes require an authenticated session, CSRF token, and an ACTIVE membership
in the organization in the path. `TenantService` also rejects disabled accounts
and organizations. A SYSADMIN role conveys no tenant access.

## Resolve care profiles

`POST /api/v1/organizations/{organizationId}/shared-care/resolve`

```json
{"ownObservationRefs":["<recipient RawObservation UUID>"],"limit":50,"offset":0}
```

`ownObservationRefs` must contain 1–50 distinct, non-null references. Every reference
must be persisted in the recipient organization and reference one of that
organization's claimed Nodes. Validation is all-or-nothing, before any foreign query.
Unknown, foreign, and Node/organization-mismatched references receive the same
400 response. Chip identifiers are resolved only from these rows; Cat IDs, manually
created Cat profiles, and caller-supplied chip identifiers are not evidence.
An UNKNOWN-clock observation can establish chip evidence without establishing a
precise observation time.

Response: `{ "items": [...], "nextOffset": null | integer }`.
Each profile item has exactly:

- `chipId`: a canonical string already established by the recipient's own evidence.
- `source`: `{ organizationId, displayName }`, permitted directory provenance.
- `catDisplayName`: the source's optional Cat name; this implementation deliberately
  includes that display field in the explicitly granted CARE projection.

No foreign Cat ID, status, notes, contacts, images, visits or site information is
returned. The recipient's Cat identity and name remain separate and unchanged.
Only effective CARE grants yield items. Ordering is chip string then source UUID;
no hidden or ungranted rows contribute to paging or lookahead.

## Page shared visit summaries

`POST /api/v1/organizations/{organizationId}/shared-care/visits`

```json
{"ownObservationRef":"<recipient RawObservation UUID>","sourceOrganizationId":"<directory organization UUID>","limit":50,"offset":0,"newestFirst":true}
```

Each request repeats membership, persisted proof, visibility and scope checks.
Effective CARE + VISITS are required in the database query before sorting or
pagination. Unknown, hidden, disabled, private, wrong-recipient and revoked sources
produce the same empty page. No cursor or previous resolve response authorizes a
later read; unknown cursor fields carry no authority.

Each visit item has exactly:

- `source`: the permitted organization provenance above.
- `startAt`, `endAt`: ISO instants from the persisted DerivedVisit.
- `siteDisplayName`: the frozen historical FeedingSite's display name only when
  CARE + VISITS + SITE_LABEL are effective; otherwise null.

No visit IDs, RawObservation IDs or payloads, Cat IDs, site IDs, coordinates,
addresses, descriptions, staff information, private notes or unrestricted nested
relationships are returned. No recomputation or cross-organization merging occurs.
The existing aggregator excludes UNKNOWN clocks. The projection additionally
requires reliable, same-owner/same-chip/same-site endpoint observations, excluding
invalid legacy derived rows. It never uses `received_at` to infer precise times or
durations. The current Node deployment does not reinterpret historical visits.

Ordering defaults to newest first; `newestFirst:false` selects chronological
ascending order. Ties use the visit UUID internally, which is never serialized.

## Limits, pagination, revocation and cache behavior

JSON bodies are limited to 16 KiB before deserialization, including chunked bodies;
excessive bodies receive 413 with no-store. Both routes default to 50 items and offset 0. Limits are 1–100 and offsets 0–10000.
Requests outside these bounds fail with 400. Pages read at most `limit + 1` rows;
`nextOffset` is returned only when another authorized row exists and the next
request stays within the offset bound. There is no total count. Paging is
repeatable for unchanged data. Recomputing derived visits, inserting profiles,
and changing grants can change a later offset page; it is not a historical snapshot.
Histories deeper than the bound require a later bounded keyset contract.

The existing SharePolicyService owns audience, active/discoverable-state and
scope-prerequisite semantics. It supplies the query-time predicates used by both
projections and its single-owner scope resolver. No policy mutation is added.
Revocation, hiding or disabling affects every subsequent authorization decision;
revocation cannot recall previously delivered data or abort a read already underway.
There are no reciprocal or transitive grants.

Both routes, including authentication, CSRF and validation errors, set
`Cache-Control: no-store`. There is no persistent shared-data cache. PHOTO remains
part of the policy vocabulary without image URLs or a media bypass; #95 owns media
authorization. #98 owns transient PWA display and navigation cleanup.

A bounded in-memory limiter permits 120 requests per account per minute across
both routes and all recipient contexts, returning 429 afterward. Once-per-window
warnings audit rate-limit violations and ten invalid proofs. Logs include only the
reader account, never chips, observation references, foreign identities or results.
The limiter holds at most 10000 active account windows and fails closed if full.
A multi-process deployment must apply an additional installation-wide gateway limit;
process restarts reset local windows. Operational log retention follows installation
policy rather than storing query payloads in the sharing-policy mutation ledger.

## Query support and compatibility

The observation proof is one primary-key-bounded scalar query. CARE resolution
uses one scalar Cat projection over the proven chip batch, with correlated
policy/recipient predicates; there is no per-chip/per-owner fetching. Visit reads
project scalar fields in one bounded source-and-chip query. Existing policy and
recipient uniqueness indexes support grant checks. V14 adds only the missing
chip-first Cat index and organization/chip/start/UUID visit pagination index.

ADR 0016's provisioning compatibility gate is enforced: the existing public
Node-owner projection retains Node UUID and CLAIMED state for recovery, but
redacts all owner organization metadata for hidden or disabled organizations.
Discoverable active owners retain their existing deliberately public owner hint.
This does not promise radio/device anonymity and does not expose cat or visit data.

`SharedCareTest` uses the existing database-configurable backend integration
infrastructure, and is tagged `auth` and `visits`. CI runs it against real PostgreSQL.
It covers positive projections and negative A/B/C authorization cases, proof
validation, revocation between pages, clock/site truth, HTTP protections, exact DTO
fields, bounded batches and a constant SQL-statement count across many sources.

## Integrated security review

One integrated closeout reviewed the observation-to-chip boundary, tenant gate,
shared policy predicates, exact DTO allowlists, pagination/lookahead, revocation,
clock/site provenance, request limits, logging, indexes and public provisioning
compatibility. Repairs tightened proof to claimed Nodes, ensured decoded route
spellings receive the same cache/body protections, and explicitly declared nullable
DTO fields in the generated contract. Positive and negative tests cover those
repairs. No frontend display, policy mutation, demo/bootstrap or media infrastructure
is added by this change.
