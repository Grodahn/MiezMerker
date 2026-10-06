# Field PWA after Epic #30 (#38)

The field PWA contains login (`/login`), `/sync`, `/nodes` and `/cats`.
Authenticated navigation has exactly Vor-Ort-Sync, Nodes and Katzen for MEMBER
and ADMIN users. Current user, organization selection and logout remain in the shell.
Fresh unauthenticated browsers see only login. The #31 loading/login/authorized
offline-sync state machine is unchanged, including session expiry, logout,
reconnect and discarding data/drafts on account or organization changes.

Spring MVC/Thymeleaf owns the online ACTIVE-ADMIN surface:

- `/admin/login`
- `/admin/`
- `/admin/members`
- `/admin/sites`
- `/admin/cats`
- `/admin/observations`
- `/admin/visits`

Cats deliberately exists in both surfaces. Field Cats remains MEMBER-capable,
including observed-chip registration, editing, sighting information and per-cat
visit history. Nodes still reads FeedingSite labels, deployment assignments and
history and retains its existing metadata/assignment workflow. FeedingSite
master-data maintenance belongs to `/admin/sites`; Nodes does not implement it.
When no sites exist, Nodes asks staff to contact an administrator.

## Removed PWA scope

`/sites`, `/observations` and `/visits` show the same small not-found state after
authentication, with links to the valid field areas. They do not fetch the former
page data or redirect MEMBER users to Admin. `/admin/members` is no longer a PWA
route: the backend's security and HTML response take precedence.

The Site editor, Members list, Records filters/pagination/recompute UI and raw
observation table are removed. The shared visit table is retained separately for
Cats. Backend services, repositories, REST endpoints and generated OpenAPI output
are unchanged. Historical #11 documentation carries an architecture follow-up note.

## Routing and offline boundary

Vite development and production preview proxy `/admin` and `/admin/**` to Spring,
as they already do for `/api`. The default target is port 8080;
`MIEZMERKER_BACKEND_URL` can select an isolated local backend for verification. The production worker excludes both namespaces
from its navigation fallback and caches no business HTTP responses or Admin HTML.
The PWA manifest can retain its existing root scope and `/sync` start URL.

There is no Spring SPA forward/fallback or bundled React static shell in this
repository. Spring's controllers and security own Admin directly. Production
hosting must route `/admin` and `/admin/**`, as well as `/api`, to Spring **before**
applying the static PWA `index.html` fallback. External reverse-proxy deployment
configuration is not part of this repository.

Offline `/sync` still uses the established device-bound credential, BLE
challenge/authorization, Dexie observation persistence, contiguous ACK/watermark,
Outbox and separate backend upload. A cold offline installation without valid
authorization still shows only login. Admin is online only; it must never become
a cached field application shell. Physical Web Bluetooth validation remains the
separate procedure in [Collector validation](collector-validation.md).

## Verification

Frontend unit tests cover exact field navigation, retained Cats/Nodes, obsolete
route not-found states, auth transitions and tenant/draft isolation. Existing
Collector/BLE/offline suites are retained. Chromium checks desktop and mobile
field workflows, offline credentials and Outbox, and real Spring login HTML from
direct Admin URLs after the worker takes control. An offline Admin navigation
must fail instead of receiving the cached PWA shell. Backend Admin integration
tests render all six authenticated Admin pages, preserving the existing business
and security coverage. TypeScript/Vite/PWA build and API drift checks complete
the verification.
