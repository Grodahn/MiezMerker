# Shared care in existing Cat details (#98)

The field PWA's existing Cat detail (including observed unknown chips) and the
server-rendered Admin Cat detail now mount the same read-only presenter. Own
names, profile editing, local visits and local activity statistics are unchanged.
No foreign profile entity, photo, private note, status or staff data is used.

## Backend contract and scopes

The presenter first requests one persisted observation through
`GET /api/v1/observations`, explicitly scoped to the current organization and
local chip. Without a matching own observation reference it makes no shared
query. An UNKNOWN-clock observation can prove a chip; it never supplies a
foreign visit timestamp.

- `POST /api/v1/organizations/{organizationId}/shared-care/resolve` receives
  `ownObservationRefs` and server pagination. CARE permits the separately named,
  provenance-marked foreign profile.
- `POST /api/v1/organizations/{organizationId}/shared-care/visits` receives
  `ownObservationRef`, `sourceOrganizationId`, pagination and `newestFirst`.
  CARE + VISITS permits stored DerivedVisit summaries. SITE_LABEL additionally
  permits feeding-site names. No frontend derivation from RawObservations occurs.

Both endpoints and all authorization/proof checks remain the implementation
from #97. Every page request refreshes CARE and VISITS; `nextOffset` from the
server drives pagination. Mobile uses 5 entries per page; Admin uses 20. Each
sharing organization has its own visit pages and visible provenance. Timestamps
are explicitly UTC; absent/invalid times render as unknown without durations.

The projections do not expose scope metadata. Accordingly, empty visit histories
and unavailable VISITS permissions share a neutral empty state; no grant and
hidden organizations share the same empty care state. Foreign names never become
the local Cat name, even for an unknown chip.

## Transient lifecycle

All requests use `cache: no-store`; shared POST responses retain #97's
`Cache-Control: no-store`. The presenter writes only text and controls into its
live DOM. It does not write to Dexie, IndexedDB, Web Storage or Cache Storage.
The existing PWA worker has no runtime business-data cache and excludes API and
Admin navigation from its fallback. Static presenter code/CSS can be cached;
foreign data cannot.
Vite development and preview proxy the three Admin presenter resource URLs to
Spring explicitly, retaining the backend session gate and correct script MIME.

Refresh and pagination immediately erase prior projections. Abort controllers,
request identity and departure guards discard late responses. Offline, blur,
hidden document, pagehide and Admin form submission clear the DOM. Local PWA
Cat form validation errors leave the shared panel usable. The PWA reuses the
management context generation/session lifecycle to unmount on organization,
role, verification, logout and route changes. Admin returns through its existing
server detail controller on focus/history return, rechecking its server-selected
organization instead of reusing the old document's context.

Revocation is reflected on refresh, any page request or return to the document.
There is no pushed revocation event in #97, so a continuously visible idle page
cannot discover revocation until the next request. No old projection is used as
a fallback after a failed request. Foreign organization identifiers appear only
in transient request bodies, never in navigation URLs, logs or error text.

## Verification

Focused frontend tests cover scope combinations, own proof, hidden/no/revoked
grants, multiple organizations, pagination, empty/UNKNOWN cases, safe text,
read-only foreign profiles, no-store/CSRF adapters and session/context cleanup.
Playwright checks 320/390/430px layouts, keyboard operation, request races,
logout/expiry/org switch/navigation/offline/pagehide, browser stores/caches and
the actual static Admin adapter. Its API fixtures are mocked; AdminCatsTest
checks real authenticated template/resource delivery. The unchanged SharedCareTest
exercises the real backend authorization and scope matrix. Mocked browser tests
are not a substitute for the #97 authorization tests or PostgreSQL CI.

```text
python scripts/test.py frontend src/management
python scripts/test.py backend --test SharedCareTest
python scripts/test.py backend --test AdminCatsTest
python scripts/test.py backend --test FoundationTest#exportActualBackendOpenApi
cd app
npm run api:check
npm run build
npm run test:e2e -- e2e/shared-care.spec.ts
```

Integrated review scope: existing detail wiring, generated DTOs, #97 scope/proof
queries, no-store responses, PWA worker, session/context invalidation, Admin
return path, pagination, safe text rendering, responsive and keyboard semantics.
Review correction: preserve table roles in the mobile card layout and restore
focus after pagination. Visual verification also corrected caption layout when
tables become mobile cards. Final delivery checks also added explicit Admin
static-resource proxy routes and a session-gate browser regression. No policy
engine, sharing settings (#93), firmware,
BLE synchronization, demo seeding or media infrastructure (#95) changes.

Post-implementation PR review found no P1 issue. Three P2 lifecycle/accessibility
findings were corrected: local PWA saves no longer trigger Admin navigation
cleanup, visibility/focus return events initiate only one Admin context reload,
and removed/disabled pagination controls restore focus to an enabled refresh or
retry control. Regression tests reproduce these cases, including rejected saves
and keyboard pagination after revocation.
