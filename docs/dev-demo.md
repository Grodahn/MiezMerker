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
For a physical Android phone connecting to the ESP32 via Web Bluetooth, use
the **HTTPS** setup below instead of the HTTP loopback-only development server.
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

## Local HTTPS for Android / Web Bluetooth (IntelliJ + Vite)

Chrome on Android requires a **secure context** for Web Bluetooth. A laptop's
LAN address such as `http://192.168.68.155:5173/` is **not** a secure
context; Android's `localhost` exception does not apply to your laptop's IP.

Use a locally trusted `mkcert` development certificate, scoped to your
computer/phone. From a Windows PowerShell in the repository root:

```powershell
winget install --exact --id FiloSottile.mkcert
# Open a new shell if mkcert is not immediately found.
mkcert -install
New-Item -ItemType Directory -Force app\.cert | Out-Null
# Replace 192.168.68.155 with the current laptop Wi-Fi address:
mkcert -key-file app\.cert\dev-key.pem -cert-file app\.cert\dev-cert.pem 192.168.68.155 localhost 127.0.0.1
cd app
npm ci
npm run dev:https
```

In IntelliJ, use the `app` working directory and either run
`npm run dev:https` in its terminal or configure an npm Run Configuration
with script `dev:https`. This script binds Vite to `0.0.0.0:5173` and
activates HTTPS using the files in `app/.cert/`. Regular `npm run dev` stays
HTTP/loopback for existing development and CI behavior. Backend `/api` and
`/admin` requests continue through Vite's existing HTTP backend proxy, so no
second public backend endpoint is required.

On the **Android test device**, explicitly install/trust the **public**
`rootCA.pem` from the directory shown by `mkcert -CAROOT` as a CA certificate,
following that device's Android security/certificate settings.
**Never copy, upload or commit `rootCA-key.pem` or `dev-key.pem`.**
Installing a private development CA gives it certificate-signing trust on that
device: use only personal test hardware, secure the CA key on the laptop and
remove the CA certificate when testing is done. The project's
`app/.cert/` is ignored by Git.

Open `https://192.168.68.155:5173/` on the phone (substitute the address
used when generating the certificate). The browser must accept TLS **without**
a certificate warning. Then `window.isSecureContext` should be `true`, and
`navigator.bluetooth` should exist in Chrome on a supported Android device.
A certificate warning or HTTP URL is **not** an acceptable Web Bluetooth fix.
If the LAN IP changes, regenerate the leaf certificate for the new IP. Check
Windows Firewall private-network access to TCP 5173 if the phone cannot connect.

The HTTPS files are optional and **not needed** for production builds or CI.
For production use deploy a publicly trusted HTTPS certificate; never deploy
the local `mkcert` private keys.

### Optional: Download the public mkcert CA directly on your own Android device (#150)

For developer-owned devices on a trusted local WLAN only, a standalone **HTTP**
helper can transfer the public CA file without manually copying it over USB.
This does **not** install the CA or make the browser trust it automatically.
For external testers use the future publicly trusted HTTPS/staging solution
(#149 / #152), not this workaround.

Prerequisite: finish `mkcert -install` and the `app/.cert/dev-cert.pem` /
`app/.cert/dev-key.pem` generation described above. You do **not** need to
copy a root CA file into the repository: the helper obtains the current mkcert
CA location using `mkcert -CAROOT`, reads only `rootCA.pem`, and converts the
public certificate to Android-friendly DER `.crt` bytes in memory.

In a **second** IntelliJ terminal, from `app/`:

```powershell
npm run test:dev-ca
npm run dev:ca -- --host 192.168.68.155
```

Replace that address with the actual Wi-Fi IP of **your own development
computer**. The helper binds only that explicit RFC1918 IPv4 address on
port **5174**, never `0.0.0.0` or a public IP, and does not start during
`npm run dev`, `npm run dev:https`, builds, CI or backend startup. It serves
only one read-only URL; all other routes return 404, with no directory listing,
app/backend proxy or private-key access.

On the Android phone on the same trusted WLAN, **type directly into Chrome**:

```text
http://192.168.68.155:5174/__dev/cert/rootCA.crt
```

This intentionally uses **HTTP** for the one-time transfer because Android
does not yet trust the development HTTPS certificate. If the file doesn't
download, verify that the computer is reachable from the phone, the IP hasn't
changed, and the Windows firewall permits the selected private-network port
5174 (do not forward it on the router). The script logs the public CA's
**SHA-256 certificate fingerprint** locally in the IntelliJ terminal.

**Important trust step:** local HTTP can be tampered with by another device on
the network. Before granting CA trust, verify the downloaded certificate's
SHA-256 fingerprint against the value from the developer's computer **over a
separate trusted channel**. Merely displaying a fingerprint on the same HTTP
download page would not verify anything. If the phone cannot verify the
fingerprint reliably, transfer the file via a trusted cable instead or use the
proper domain-based staging setup in #152.

Then navigate Android Settings (names vary by device) to **Security / More
security settings / Encryption & credentials / Install a certificate / CA
certificate**, choose the downloaded `.crt`, and explicitly confirm Android's
security warning and device unlock. A webpage cannot approve this on behalf
of the user. Chrome may need to be restarted. After installation open:

```text
https://192.168.68.155:5173/
```

Chrome must show a valid certificate **without warnings**, with
`window.isSecureContext === true`. If Bluetooth is still missing, investigate
browser support and permissions separately.

Stop the CA transfer helper with **Ctrl+C** as soon as the download finishes.
Remove the privately installed CA from the Android trusted credentials when
development testing is complete. Never transfer `rootCA-key.pem`,
`dev-key.pem`, or `app/.cert/` to a test phone. The endpoint must not be
present in released deployment artifacts; see mandatory release gate **#151**
and the parent production release gate **#113**.
