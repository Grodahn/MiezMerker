# MiezMerker architecture

Issue [#2](https://github.com/Grodahn/MiezMerker/issues/2) is this foundation's
implementation contract. Subsequent tickets add features within these boundaries.

## Components and ownership

| Directory | Responsibility | Build / verification |
| --- | --- | --- |
| firmware-core | Portable C++20 core; Clock, Storage, RfidReader, BleTransport ports | Root CMake / CTest |
| firmware-esp32 | ESP-IDF platform composition and future board adapters | Host composition target today; board image in firmware tickets |
| simulator | Inject simulated ports into the actual firmware-core library | CMake / CTest, no hardware |
| protocol | BLE logical record schema/fixture and wire boundaries; future GATT codecs | Schema/fixture checked by app tests |
| app | **One** React/TypeScript/Vite installable PWA: collector and administration | npm ci / tests / build / browser tests |
| backend | Java/Spring Boot HTTP API + server-rendered ADMIN backoffice (`/admin`, Thymeleaf, ADMIN-only); Security, JPA, PostgreSQL, Flyway, OpenAPI | Maven wrapper verify |
| docs | Domain, trust boundaries, ADRs and acceptance review | Documentation review |
| hardware | Schematics/BOM/reader/power work | Documentation placeholder today |

The portable core cannot import ESP32, ESP-IDF, OS or browser APIs. Platform
adapters implement ports; the simulator links the very same core sources as the
ESP32 composition boundary. No duplicate simulated business logic is allowed.

## Domain vocabulary

The following is a model contract, **not** a set of speculative JPA entities or
frontend copies of future HTTP DTOs. Backend domain/API tickets introduce those.

| Concept | Meaning and relationships |
| --- | --- |
| Organization | Tenant and scope for all business records, including sites, Nodes, cats, observations, visits and upload receipts. |
| User | Global login identity (email/password); may participate in multiple Organizations. Not itself a tenant-owned business record. Carries the global human-readable `AppUser.displayName` (#32, nullable, max 255, trimmed, Unicode; UI falls back to email when absent). The name describes the person, not a membership: one user shows the same name in every organization. |
| OrganizationMembership | User ↔ Organization link. **Role** answers which permissions apply; **status** answers whether access is currently allowed. An administrative role never overrides an inactive membership. Exact role/status sets belong to #16. |
| AppDevice | Local installation identity with its own asymmetric key pair; backend registration connects public key, User and authorized Organization context. Private keys remain local; lifecycle and key protection belong to #17. |
| FeedingSite | Organization-owned physical feeding location. |
| Node | Stable hardware identity, claim/ownership lifecycle and reset incarnation. Does not have a timeless current-site relationship used for historical interpretation. |
| NodeDeployment | Organization-scoped assignment Node ↔ FeedingSite with `valid_from` inclusive and nullable `valid_until` exclusive. Intervals for a Node must not overlap; moves close one interval and open another. Ownership-transfer semantics belong to the lifecycle/domain tickets. |
| Cat | Organization-owned record mapping chip identity to the managed animal. Cross-tenant chip matches confer no access. |
| RawObservation | Immutable primary data from a Node: event identity, chip reading, recorded timestamp and clock-quality context. A stable identity must incorporate Node/incarnation/sequence so retries and factory reset do not collide. Retain source bytes; never overwrite a prior observation with a conflicting retry. |
| DerivedVisit | Secondary server-side result computed from observations under a versioned derivation policy. May be rebuilt; source observations cannot be rewritten. No firmware/PWA visit aggregation. |
| NodeSync | Local transfer lifecycle for a specific Node/AppDevice: connection, authorization, receiving, durable local commit, acknowledgement/resume. No HTTP or Internet prerequisite. |
| AppOutbox / PendingUpload | Durable local queue of transport envelopes awaiting authenticated backend upload. Partition by Organization/AppDevice/Node context; remove only after server acceptance. This local envelope is not a hand-maintained HTTP DTO. |

Historical attribution uses the deployment valid **at observation time**, never
the Node's present FeedingSite or upload receipt time. Keep deployment history
and link observations/derived results to the resolved historical deployment.
Uncertain or missing device time/deployment is explicitly unresolved until the
backend's clock-resolution policy can decide; guessing the current site is forbidden.
Moving a Node must not rewrite raw data or reassign old visits. Server ingestion
must validate tenant ownership for the appropriate historical context; a later
claim does not authorize an uploader to access another Organization's old records.

## Data flow and independent sync lifecycles

```text
RFID read → Node immutable raw log → BLE → PWA durable collector storage/outbox
                                                   ↓ later, online
                                          authenticated HTTP API
                                                   ↓
                                      immutable RawObservations (PostgreSQL)
                                                   ↓ backend only
                                          versioned DerivedVisits
                                                   ↓ HTTP API
                                      administration in the same PWA
```

NodeSync progresses independently from backend sync. Local commit precedes the
Node acknowledgement; an interrupted transfer must be resumable. Backend sync
can wait for Internet/session renewal and retry idempotently. Backend deduplicates
by stable event identity and rejects conflicting content. Failure of backend
upload cannot undo local durable acquisition. #2 provides separate state types
and an outbox adapter, not transitions, acknowledgements, observation capture or
real upload/aggregation implementations.

## Field PWA, Admin backend and the offline boundary

After Epic #30 / #38, `/app` exposes login (`/login`), `/sync`, `/nodes`
and `/cats`. Former `/sites`, `/observations` and `/visits` URLs show a small
not-found state inside the authenticated field shell. Nodes and Cats use
transient, organization-bound server data without mounting collector storage.
Nodes still reads FeedingSite context and deployments; FeedingSite master-data
maintenance belongs only to the Admin UI. Cats deliberately remains MEMBER-capable
in the PWA as well as available in the Admin backend, including per-cat visit history.

Spring MVC/Thymeleaf owns `/admin/**`: `/admin/login`, `/admin/`,
`/admin/members`, `/admin/sites`, `/admin/cats`, `/admin/observations`,
`/admin/visits`. These online pages retain the ACTIVE-ADMIN gate from #33.
RawObservations and Visits no longer have standalone PWA pages. Shared domain
objects, REST APIs and generated OpenAPI types remain available.
See `docs/issue38-field-pwa.md` for the final surface split and routing boundary;
`docs/issue11-management.md` describes the historical combined PWA.

Unauthenticated browsers see only login (#31; see `docs/issue31-auth-gate.md`).
The centralized app-shell gate renders login-only, offline-sync-only (`/sync`
with a still-valid offline credential, never anonymous) or the authenticated
app. It never replaces backend tenant/role checks.

The production Service Worker precaches the shell and collector assets and uses
an SPA navigation fallback excluding both `/api` and `/admin` namespaces. It never caches authenticated HTTP
business responses. Install/first use needs an online visit; thereafter `/sync`
can reload offline. The Vite dev server intentionally does not register a worker;
use a production build/preview to verify offline behavior. A future update UI
must coordinate worker updates with active transfers; no auto-reload mid-sync.

Dexie schema versions own local migrations. The v1 upload store is minimal,
isolated behind `Outbox`, and opens only in the collector. Outbox batch IDs are stable and globally unique;
identical retries are idempotent, while conflicting payloads or metadata are
rejected in one IndexedDB transaction without overwriting queued data. Management pages do not
need NodeTransport, Crypto or collector storage. NodeTransport and AppDeviceKeys
are interfaces with explicit unconfigured adapters; they cannot perform real BLE
or issue/sign credentials yet. Service-worker registration is also an adapter.
Local queues are scoped for correctness, not a security boundary: browser storage
and client-supplied Organization IDs are untrusted. Shared-device logout/account
switch and data-retention policies belong to auth/collector work before production.

Android with a current Chromium browser (primarily Chrome), HTTPS or trusted
localhost, and user-initiated Web Bluetooth are the collector target. Unsupported
browsers may use management; collector capability handling belongs to #8. There
is no native Android application in the MVP. See ADR 0010.

## Trust boundaries

| Boundary | Inputs are untrusted | Required authority / checks |
| --- | --- | --- |
| Backend ↔ PWA | Requests, tenant IDs, queued payloads, local key claims | Backend session identifies User; active membership and server-derived permissions scope every query/mutation. Validate Organization of referenced objects and historical deployment. HTTPS, protected session cookies and CSRF for mutations. |
| PWA ↔ Node | Radio peers, advertisements, timestamps, replayed frames | Offline, per-AppDevice credential and proof of its private key; Node validates issuer, scope, validity and challenge/replay protections. No online backend dependency. |
| Backend issuer ↔ Node | Claim/reset state, presented credential | Node has a defined issuer trust anchor; provisioning, signed credential format, freshness and reset/claim binding belong to #17/#18. No shared employee Organization secret. |

**Backend login/session authentication and offline BLE authorization are separate.**
An email/password login establishes an HTTP session; its cookie is never sent to
the Node. A backend-issued scoped credential plus a local AppDevice key authorizes
an offline BLE action; it is not an HTTP login or unconditional tenant access.
Revocation cannot propagate instantly while a Node is offline; bounded validity,
clock trust and refresh/revocation behavior must be specified in #17. Do not claim
these placeholder interfaces enforce authorization today.

Business endpoints resolve active membership server-side before reading or writing
data, including lists, object IDs, uploads, derived results and admin operations. Never
trust a request's Organization alone. Login/session, membership enforcement, CSRF and
cookie Secure policy behind HTTPS are implemented (#16, ADR-0014).

## HTTP and persistence contracts

Backend records/controllers own HTTP DTOs. Springdoc publishes `/api/v1/openapi`
with API version `v1` and relative same-origin server `/`. The backend integration
test exports that exact running-server document to `backend/target/openapi.json`.
The generator sorts object keys (preserving array order), snapshots it as
`backend/openapi/v1.json`, and generates `app/src/api/generated.ts` using pinned
openapi-typescript. `openapi-fetch` consumes those generated paths as the typed
runtime client. Never introduce independent frontend versions of backend DTOs.

CI reexports and checks both committed files byte-for-byte, twice, so API changes
must deliberately update the generated artifacts. Breaking changes require a
documented API version/migration decision. BLE/GATT/flash byte formats are owned
by `/protocol` and firmware ADRs, not by OpenAPI.

PostgreSQL is the production/dev database, Flyway owns schema evolution and JPA
uses `ddl-auto: validate`. The first migration only reserves a schema namespace.
Domain tables and Flyway history live in `public`; the connection pool pins every
connection to that schema. The reserved `miezmerker` namespace must not alter
PostgreSQL's lookup path when the database user has the same name.
Tests use H2 by default for a portable smoke check and the same tests run against
PostgreSQL in CI. No business entities, raw ingestion or visit engine are built
in #2. Same-origin reverse proxy deployment is preferred; Vite proxies `/api` and `/admin/**` to
port 8080 locally without CORS configuration. Serve static PWA files with an SPA
fallback, route `/api` and `/admin/**` (including bare `/admin`) to the backend before that fallback, and require HTTPS in
production. Production proxy/hosting configuration belongs to deployment work.

## Decisions and deliberately unfinished features

See `docs/adr/` for decisions. Implemented: tenancy/session auth (#16, ADR-0014),
offline credentials with per-AppDevice keys (#17, ADR-0012), node identity/claim
(#18, ADR-0013), portable raw observation capture with durable sequence/RTC
(#5, ADR-0012-firmware-core-observations), and organization-scoped domain with idempotent
ingest (#9). Detailed RFID, ESP32 flash adapters, GATT codecs, collector
transfers, admin features, derivation, invite-onboarding (#19), real backend upload and
visit engine belong to #5–#19. This foundation plus #16–#18 establishes ownership,
contracts, auth and claim boundaries. Host ESP32 composition builds; a flashable ESP-IDF
image requires actual board adapters and is outside #2.
