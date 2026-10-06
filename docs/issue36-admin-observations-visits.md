# Admin observations and visits (#36)

> Final closeout (#30/#38): this document records its implementation stage.
> All Admin pages are now implemented. The field PWA retains `/login`, `/sync`,
> `/nodes` and `/cats`; Members, Site maintenance, Observations and standalone
> Visits use the server-rendered Admin backend. Cats remains in both surfaces.
> See [the final split](issue38-field-pwa.md).

`GET /admin/observations` and `GET /admin/visits` reuse the #33 layout, login,
ACTIVE ADMIN gate and validated session organization. Members and inactive
memberships cannot use these pages or recompute. A multi-organization admin
must select an organization through `/admin/org`; every request validates it.

## Filters and pagination

Both pages support exact FeedingSite UUID and normalized chip ID, and a UTC
range (`from` inclusive, `to` exclusive, entered as datetime-local values).
Observations also accept Node UUID and exact decimal sequence. The observation
time filter compares the stored RTC milliseconds, never receipt time; visits
filter the persisted start time. UNKNOWN rows without a timestamp are visible
without a time filter. Foreign/absent filter resources return the same empty
result, without disclosing names, chips or locations. Invalid form values show
neutral validation feedback. Time filters must be nonnegative Unix times within
the existing finite PostgreSQL observation-time range. The reset link removes all filters.

Pagination uses `limit` (default 50, 1–100) and nonnegative `offset`. The shared
query service retrieves at most limit + 1 rows for next-page detection; no full
history is loaded. Raw ordering is received_at, sequence, UUID descending;
visits retain API ordering by start, chip, FeedingSite and UUID ascending.
Site/Node/Cat display associations are fetched with the bounded page query.
Filters stay in GET parameters, are not stored in sessions, cookies, analytics
or browser storage, and are not logged by this feature. Changing organization
loads fresh data; filters are never restored from previous tenant state.

## Raw versus derived

Observations display immutable sequence, raw RTC milliseconds, clock status,
received_at, Node, historical FeedingSite/deployment, incarnation, monotonic
milliseconds and boot counter. UNKNOWN/null clock time is explicitly unknown;
invalid values remain raw and are not corrected. Backend receipt time remains
separately labeled. It never becomes a fabricated observation timestamp.

Visits display persisted start/end, ISO 8601 duration from those endpoints,
historical FeedingSite, Cat when known, chip, read count, algorithm version,
gap seconds and first/last raw observation UUIDs. An unmapped chip is valid.
Neither page aggregates observations or repairs clocks. FeedingSites use the
frozen associations stored by ingestion/aggregation, never current Node
placement, so moving a Node does not reinterpret old records.

## Recompute

`POST /admin/visits/recompute` requires Spring Security CSRF and ACTIVE ADMIN.
It calls the existing #10 aggregation service with its default parameters,
replacing only the active organization's visits for its supported algorithm.
Raw rows and other tenants' visits are unchanged. The form's organization UUID
is only a stale-form guard: authority remains the validated session context.
An organization switch rejects a previous tenant's open recompute form.
Success reports visit/exclusion counts; supported service errors produce
neutral failure feedback, including database errors after rollback. No second aggregation implementation is introduced.

The PWA `/observations` and `/visits` continue to coexist until #38. REST
contracts and the generated client are unchanged; REST and MVC share the
extracted query layer. #34/#35 behavior and the merged #37 cats Admin pages are intact.
