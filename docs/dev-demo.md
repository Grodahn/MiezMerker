# Local DEMO ONLY setup (#111)

This explicit operator command creates persistent master data in a local development
PostgreSQL database. Normal application startup never seeds it. It works on empty
and existing databases without adopting or modifying unrelated records. Use only an
isolated local development database: loopback is a guard, not proof that the data is
non-production. Never point a tunnel at production. #113 is the mandatory production
release cleanup gate; the cleanup workflow is deliberately not implemented here.

Prerequisites: Java 21, PowerShell, Maven wrapper dependencies, and a running local
PostgreSQL database. The repository's `docker compose up -d --wait postgres` starts
the usual development database when Docker is available. Use its existing credentials
or your own local operator secrets. Setup has no default DB credentials.

Set `SPRING_PROFILES_ACTIVE=dev`, `DB_URL` (for example
`jdbc:postgresql://127.0.0.1:5432/miezmerker`), `DB_USER`, `DB_PASSWORD`, and
`MIEZMERKER_DEMO_PASSWORD` in the local process environment. Inject the agreed
public development test password from issue #111 into `MIEZMERKER_DEMO_PASSWORD`.
It is intentionally absent from committed source, configuration and production
defaults. No password prompt or database editing is required by setup.

From the repository root, one setup invocation:

```powershell
./scripts/dev-demo.ps1 -Development
```

The script builds the backend and invokes `java -jar
backend/target/backend-0.1.0-SNAPSHOT.jar dev-demo`. The Java command also requires
`MIEZMERKER_DEMO_ENABLED=true` when used directly. Only the exact `dev` profile and
an explicit loopback PostgreSQL URL with port and no URL options are accepted.
Production, combined or unknown profiles, remote targets and missing opt-in fail
before Spring or migration startup. Included/additional profiles fail before refresh.
The verified database URL and environment credentials are pinned for both Flyway
and persistence; general Spring datasource overrides cannot redirect the seed.
This command runs a separate non-web Spring context, applies normal migrations,
uses the existing repositories and BCrypt (cost 12), then closes. It does not run
ordinary organization bootstrap or open an HTTP listener.

Start the backend normally afterward (`cd backend; ./mvnw.cmd spring-boot:run`;
use `COOKIE_SECURE=false` for local HTTP) and the PWA (`cd app; npm ci; npm run dev`).
Open `http://127.0.0.1:5173/`, log in as `admin@miezmerker.local` with the password
injected above, and select **MiezMerker Demo** (`miezmerker-demo`). The account is
ACTIVE, with an independent ACTIVE ADMIN membership. SYSADMIN is granted only via
the existing audited `SystemRoleMaintenance.apply` operator mechanism, with a stable
operation UUID derived from fixture version and owned user UUID. Replays do not
restore a revoked privilege. SYSADMIN gives no access to other tenants' data.

| Literal chip_id string | Cat | Stored description (notes) |
| --- | --- | --- |
| `"1"` | Herr Krümel | Kontrolliert jeden Napf und trägt die letzten Krümel stolz im Schnurrbart. |
| `"2"` | Frau Zimt | Eine sanfte Sonnenanbeterin, die Streicheleinheiten mit leisem Schnurren quittiert. |
| `"3"` | Käpt'n Socke | Mutiger Gartenentdecker mit weißen Pfoten und großem Appetit auf Abenteuer. |
| `"4"` | Lotte Löffel | Die neugierige Küchenchefin schaut erst in alle Näpfe und entscheidet dann. |
| `"5"` | Professor Flausch | Denkt lange über das Futter nach und schläft anschließend auf seinen Erkenntnissen. |

Exactly one real FeedingSite, **Zum schnurrenden Löffel**, is created with description:
“Das kleine Katzenbistro: Hier gibt es volle Näpfe und zufriedene Schnurrkonzerte.”
Coordinates and location label are unset. These ordinary Cat/FeedingSite records appear
through existing APIs and UI. Cat internal IDs remain generated UUIDs. #112 can send
chip strings `"1"` through `"5"` directly, with no mapping. Register/claim the physical
ESP32-C3 and assign this feeding site through the normal NodeDeployment workflow.
Setup creates no nodes, deployments, observations or visits.

## Ownership, reruns and cleanup boundaries

`DemoFixtures` is the versioned source fixture. V13 creates only an empty ownership
ledger and its lock row. In `demo_fixture_records`, fixture version
`DEMO-ONLY-issue111-v1` records exact generated UUIDs under `organization`, `user`,
`membership`, `feeding-site`, and `cat-1` through `cat-5`. Names, email patterns and
short chip IDs alone are never ownership evidence. The `lock` entry owns no domain
record. The ledger intentionally has no cascading foreign keys: later deletions
remain detectable rather than silently allowing setup to adopt replacements.

Master records and ledger entries commit atomically under the fixture lock. The
audited grant follows in its own existing maintenance transaction. If it fails,
rerun setup to finish the same operation; do not delete the ledger or audit history.
An unowned email/slug causes a clear failure with no seed writes. A partial ledger,
missing/replaced cat or site, altered chip/ownership, extra cat/site, disabled account,
disabled/demoted membership or different supplied password fails without repair.
Existing demo names, notes, site descriptions, coordinates and password hashes are
preserved on rerun. Final verification uses the existing global authorization gate,
including current ACTIVE account status. Missing/revoked privilege or an inactive
account fails with recovery guidance and is never repaired automatically; use
the documented trusted SYSADMIN recovery procedure if explicitly intended.

#113 must use this ledger and revalidate relationships before any removal. The
initial cleanup boundary is the nine recorded master records plus the demo user's
global privilege; retain the SYSADMIN audit/replay ledger per `docs/sysadmin.md`.
Audit rows reference the user via a foreign key, so #113 must explicitly resolve
account retention/anonymization before physical account deletion; revocation and
disabling must precede any production exposure. Do not drop audit history to bypass
that dependency.
Nodes, deployments, observations, visits, other memberships or sharing data created
later are not automatically owned by the initial fixture. Cleanup must inspect
those dependencies and stop for operator review rather than blindly cascade or
delete the whole organization/user. Do not delete other tenants' cats even if their
chip strings are identical. Removing the demo account/data before go-live remains
mandatory; the known credential must never exist on a production-accessible instance.

Focused verification: `python scripts/test.py backend --test DevDemoTest`.
