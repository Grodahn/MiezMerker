# 0016 — Global system administration and explicit cross-organization sharing

Status: **Accepted architectural contract** (issue #90; implementation is pending in #91–#99).
Date: 2026-10-09
Related: [Epic #89](https://github.com/Grodahn/MiezMerker/issues/89),
[#9](https://github.com/Grodahn/MiezMerker/issues/9),
[#10](https://github.com/Grodahn/MiezMerker/issues/10),
[#16](https://github.com/Grodahn/MiezMerker/issues/16),
[#17](https://github.com/Grodahn/MiezMerker/issues/17),
and ADRs 0007, 0014.

## Context

MiezMerker already supports multiple organizations on a **single self-hosted
installation** with global `AppUser` accounts and organization-specific
`OrganizationMembership(ADMIN|MEMBER, ACTIVE|PENDING|DISABLED)` records.
A user can have multiple memberships and explicitly selects an active
organization. Authorization is enforced server-side using the authenticated
user and current active membership, not a user-supplied organization ID.

`Cat` is owned by one organization and is already unique per
`(organization_id, chip_id)` (`V6__issue9_domain.sql`). The same chip may
exist as differently named cats in organizations A and B.
`RawObservation` and `DerivedVisit` are also organization-owned.
The backend derives visits within the organization and preserves frozen
historical FeedingSite assignments and clock-quality semantics (#10).

The new requirement is (a) instance-wide administration and (b) explicit
opt-in, read-only sharing of limited cat-care information and visits between
organizations. Both must preserve existing tenant boundaries.

## Decision 1: System authority is separate from tenant authority

- Add **global `SYSADMIN`** as an account-level privilege independent of
  `OrganizationMembership.role`; a separate `app_user_system_roles`
  relation (or equivalent isolated model) is preferred.
- Keep membership roles **`ADMIN` and `MEMBER`** unchanged.
  Label `ADMIN` as **Orgaadmin** in the UI; do not add a redundant
  membership role called `ORGAADMIN`.
- `SYSADMIN` may administer organization metadata and assign/revoke
  organization-admin memberships via a dedicated, server-protected
  `/admin/organizations/**` surface.
- **`SYSADMIN` is not a super-reader of cats, observations, visits,
  feeding sites, or node secrets.** Access to normal organization data
  still requires a valid ACTIVE membership in that active organization,
  with its applicable tenant-specific role.
- Preserve multiple memberships per global account. A selected
  organization context must be validated for every tenant-scoped request;
  being a member of A and B must not mix their views.
- Do not auto-promote existing organization admins to `SYSADMIN` during
  migration. Initial grant and emergency recovery require an explicit,
  audited, operator-controlled bootstrap/maintenance mechanism. No
  self-signup, public role-grant endpoint, or org-admin self-escalation.
  Disable/revoke global authority when the account is disabled.
- Existing ACTIVE-ADMIN checks for tenant-specific `/admin/**` pages
  must remain in force. Adding the SYSADMIN gate must not silently
  allow access to those pages.

## Decision 2: Cats and observations retain ownership

- Keep each `Cat` record organization-local. Enforce
  `UNIQUE(organization_id, normalized_chip_id)` after checking
  legacy data and documenting a normalization policy; the current DB
  constraint is `(organization_id, chip_id)`.
- Preserve chip identifiers as canonical **strings**, including leading
  zeros. Do not turn chip IDs into numeric keys or create a global
  authoritative cat/name record.
- An observed, unregistered chip is not automatically a `Cat` record.
- Org A's cat name, notes, image and status do not overwrite Org B's.
  Only separately authorized read-only **projections** of B's fields may
  appear in A's UI.
- `RawObservation`, `DerivedVisit`, `Node`, `NodeDeployment`
  and `FeedingSite` remain owned by their existing organization.
  **No cross-organization visit recomputation or record migration.**
  Historical node deployments and `UNKNOWN` clocks retain their
  established meaning.
- Cross-organization sharing does not extend offline BLE credentials,
  node ownership or claiming (#17/#18); it is an online-backend read
  capability only.

## Decision 3: Discoverability and grants are independent

All new organizations and all migrated organizations are initially
**hidden and private**. A hidden organization is not listed in the
authenticated inter-organization directory and has **no effective
outgoing grants**. Organization names/metadata should not be exposed
via another private-data endpoint.

A per-organization Boolean `discoverable` (default `false`)
controls whether other organizations may discover/select it as a
sharing recipient. `discoverable=true` **does not share** cats or visits.

A discoverable owner's ACTIVE Orgaadmin can issue **outgoing,
read-only grants** per scope:

| Scope | Explicitly permitted data | Not implicitly permitted |
| --- | --- | --- |
| `CARE` | A short “also cared for by B” notice, source organization's permitted display name and, if deliberately included, its cat's display name | Visits, places, photos, notes, private contacts, raw reads |
| `VISITS` | Limited, paginated `DerivedVisit` summaries: start/end or appropriate reliable time plus source organization | Feeding-site label, precise coordinates, notes, raw observations |
| `SITE_LABEL` | Feeding-site *display name* on otherwise allowed shared visits | Latitude/longitude, address, location description, internal maps |
| `PHOTO` | Explicitly permitted cat image through an authorization-checked media endpoint | Other images, internal file paths, publicly guessable media URLs |

`VISITS` requires an effective `CARE` grant. `SITE_LABEL`
requires `CARE + VISITS`. `PHOTO` requires `CARE` and a distinct
media authorization check. In the first version **exact coordinates,
addresses, internal notes, private contacts and staff metadata are
never shared**, regardless of which combination is enabled.

For each scope, the owner's policy has an audience:

- `PRIVATE`: nobody outside the owner (default).
- `ALL_DISCOVERABLE`: any other **ACTIVE and discoverable** organization
  on the same MiezMerker instance with a valid active reader context.
  This is not anonymous or Internet-public.
- `ALLOWLIST`: selected ACTIVE, discoverable recipient organization IDs.

The owner controls grants; A's permission to see B does not permit A
to share B's data onward to C. No reciprocity, inherited grants, or
transitive sharing. Neither a global SYSADMIN account nor simply knowing
a chip ID gives access to shared data.

**Revocation and transitions:** hiding or disabling the owner
invalidates all outgoing grants immediately for subsequent backend reads;
disabling or hiding the recipient invalidates its access. Policies/grants
invalidated by hiding or disabling must not silently reactivate when
the organization becomes visible/active again: require fresh affirmative
activation/grant where necessary, with revisioned audit. Revoking a
specific scope/recipient takes effect on subsequent backend reads. No
promise is made that previously downloaded or exported copies can be
remotely recovered.

## Decision 4: Sharing uses constrained backend projections

**No anonymous/global chip lookup**, and no arbitrarily user-supplied
chip ID search that can enumerate foreign cats.

For the first version, the recipient must have **evidence of an actual
observation from its own authorized node**, recorded server-side, before
asking whether a particular chip has an explicitly shared care profile.
Creating a guessed local `Cat` alone is not sufficient. Imports and
external verification can be designed separately later.

A conceptual endpoint (exact HTTP contract belongs to #97) is:

```text
POST /api/v1/organizations/{activeOrgId}/shared-care/resolve
  body: { ownObservationRefs: [ ... bounded, owned references ... ] }
```

The backend validates the authenticated user, the ACTIVE membership,
the active org, each **recipient-owned** observation reference, the
owner's visibility and effective scope grant, and the active status of
both organizations **at query time**. It resolves the chip IDs
**from the authorized recipient's own observations**, not from a
free-form foreign-chip search parameter.

Only DTOs specifically projected for the effective scopes are returned
(e.g. source display name, care hint, optionally shared cat name;
separately paged visit summaries). Never return foreign domain entities,
private notes, raw observations, internal foreign IDs or unfurnished
site coordinates. Pagination requests must re-check grants, not trust
a cursor as authority. Restrict request size, rate-limit and audit
suspicious querying. Avoid leaks through existence/count/search/error
responses, timing, cache keys, cursor content or logs.

Shared data is online-only for v1, returned with
`Cache-Control: no-store`; no shared records in Dexie, PWA Cache
Storage, service-worker offline caches, or persistent browser state.
Discard transient data on logout, active-org switch, permission loss
and navigation away. Invalidation of a backend grant applies at the
next backend authorization decision; previously delivered content
may have been observed, copied or exported.

**Compatibility gate:** existing #18 behavior can reveal limited
foreign Node owner's public metadata during provisioning/diagnostics.
That older contract must be reconciled with the new hidden-organization
policy in #96/#97 without breaking essential node recovery workflows
or claiming security. Do not claim complete anonymity until this path,
including radio/device-advertisement information, has been reviewed
and explicitly tested.

## Conceptual persistence additions

Implementation specifics and Flyway versions belong to #91, #94,
#95 and #96, but the model must preserve:

```text
app_users                           existing global login identity
app_user_system_roles               new global SYSADMIN grant/revoke + audit
organizations                       existing + discoverable FALSE default
organization_memberships            existing ADMIN/MEMBER role and ACTIVE status
cats                                existing, unique (organization_id, chip_id)
organization_share_policies         new owner_org_id, scope, audience, revision
organization_share_recipients       new owner-policy to allowed recipient orgs
organization_share_audit            new actor, owner, target, scope, action, time
cat_media                           later #95: organization-owned, ACL-checked
raw_observations / derived_visits   existing, no owner change
```

Database constraints must protect owner/recipient uniqueness and
prevent duplicate grants. Owner and recipient must be distinct.
Use indexes appropriate for `(owner_org_id, scope, recipient_org_id)`
and for bounded recipient-owned chip lookups; no unbounded
cross-tenant full scans. Keep a deliberate schema upgrade path:
migrated organizations are hidden/private; migrated users gain no
global role; raw observations and visits are untouched.

## Authorization matrix (owner B, recipient A)

| Owner B | Grant B→A | A context | Visible to A |
| --- | --- | --- | --- |
| Hidden | Any | Active A with own observation | Nothing about B |
| Discoverable | None | Active A with own observation | B only in generic directory; nothing about B's chip |
| Discoverable | `CARE` | Active A with own observation | “Also cared for by B” and permitted care fields only |
| Discoverable | `CARE + VISITS` | Active A with own observation | As above plus B's paginated visit summaries; no site name without `SITE_LABEL` |
| Discoverable | `CARE + VISITS + SITE_LABEL` | Active A with own observation | As above plus explicitly permitted site display names |
| Discoverable | Grant only to C | Active A with own observation | No B chip/visit data |
| Discoverable | `ALL_DISCOVERABLE` | A discoverable and active | Appropriate scopes only, with own observation proof |
| Discoverable | `CARE` | A membership disabled, or user disabled | Denied |
| Discoverable | `CARE` | A user is also B member, active context A | Only B→A grant; no membership-based implicit transfer |
| Discoverable | `CARE` | SYSADMIN without A membership | No tenant-data access |
| Revoked/disabled/hidden | Former grant | Any | No **new** shared read; past copies cannot be recalled |

## Verification contract (issue #99; focused tests in each implementing issue)

1. Migrated and newly created organizations are hidden/private and
   old ADMINs gain no SYSADMIN role.
2. Same chip at A and B; distinct names, notes and photos; same-org
   duplicate rejected, no automatic cat merge.
3. Hidden B vs visible/private B vs `CARE` vs `VISITS` vs
   `SITE_LABEL`/`PHOTO`; no leaking of ungranted fields or records.
4. Grant to C does not confer access to A; A→B and B→C does not imply A→C.
5. A's user cannot enumerate B by guessed chip IDs, fabricated cat
   entries, direct object URLs, result counts, cursors, ordering,
   timing side channels or media file URLs.
6. User ACTIVE in A and B sees only the explicitly selected
   tenant context; `PENDING`/`DISABLED` membership and disabled
   global user are rejected; SYSADMIN alone is not a cat reader.
7. Org-admin-only grant mutation, CSRF and auditing; MEMBER cannot
   grant; owner change, hide, disable, recipient disable and
   revocation block later reads.
8. Cache/no-store, logout, context switch, browser-history restoration
   and concurrent revoke/read behavior have privacy regression tests.
9. No change to historical FeedingSite assignment, immutable raw
   observations, `UNKNOWN`-clock semantics or versioned visits.
10. Performance tests cover bounded batches and pagination, with
    no N+1 foreign-record fetching.
11. #18's foreign-node owner metadata does not bypass privacy policy;
    test any deliberately retained minimal emergency-contact behavior.

## Alternatives rejected

- **One global Cat record per chip:** erases independently owned
  names, notes and photos; conflates tenants.
- **Extending organization membership to `SYSADMIN`:** makes
  system authority ambiguous with tenant authority.
- **Opening ordinary Cat/Visit repositories to all tenants and
  filtering in the UI:** would bypass trusted authorization boundaries.
- **Global chip search or automatic sharing:** creates enumeration
  and precise-location exposure risks.
- **Physically copying foreign DerivedVisits into recipient tables:**
  complicates revocation, history attribution and source provenance.
- **Federation between separate self-hosted servers:** not required
  for this single-instance epic.

## Privacy and deployment limits

This is an engineering access-control decision, **not** a legal
determination that a particular disclosure is lawful. Administrators
must establish an appropriate processing purpose, legal basis,
retention/erasure periods, and responsibilities where personal or
sensitive location information can be involved; validate these with
the organization's privacy lead. An opt-in setting alone does not
constitute a general GDPR legal basis. Until the related policy/legal
details are confirmed, keep location coordinates, internal notes,
contacts and staff information outside the shareable API contract.

## Consequences and follow-up implementation order

- #91 global SYSADMIN model and bootstrap/recovery.
- #94 local cat identity/normalization verification.
- #96 policy persistence, scope checks, audit and revocation.
- #92 SYSADMIN organization management; #93 sharing settings.
- #97 restricted shared-care/visit projections.
- #95 explicitly authorized cat media (independent follow-up).
- #98 field-PWA/Admin presentation after #68/#69/#97.
- #99 positive and negative A/B/C security acceptance.

**Accepted ADR ≠ completed functionality.** There are no new
permissions, sharing endpoints or database tables until their
respective implementation PRs land.
