# MiezMerker

For selective test commands, compact logs, and the three-stage development
testing policy, see [Testing](docs/testing.md).

**MiezMerker is an open-source hardware and software platform for animal shelters,
rescue groups, and organizations caring for stray cats.** It is designed to
identify microchipped cats at feeding stations using low-cost RFID/ESP32 nodes,
collect observations offline in the field, and turn them into useful visit
history for animal-welfare work.

The project is intended to be freely reusable and self-hostable by interested
shelters and organizations rather than tied to a proprietary service. The first
intended real-world handoff is to an animal shelter in Düren, Germany, once the
hardware path has been validated; the multi-organization architecture is designed
so that other shelters can operate the same infrastructure independently.

MiezMerker is currently under active development and is **not yet a finished
animal-monitoring product**. Much of the software foundation already exists;
physical RFID/antenna validation and final field deployment are still pending.

## Why this project

Stray-cat care often depends on repeated manual observation: which cat visits
which feeding station, whether a known animal has returned, and whether an
unknown microchip appears regularly.

MiezMerker aims to provide an inexpensive, open alternative:

- use standard 134.2 kHz animal microchips as the identity source;
- collect observations at feeding stations with battery-powered ESP32 nodes;
- keep field collection usable without Internet access;
- synchronize observations later through a browser-based field app;
- derive visits centrally while preserving immutable raw observations;
- support multiple independent organizations with strict tenant separation;
- keep hardware, protocol, backend, and application code openly inspectable and
  reproducible.

The project also serves as a practical experiment in agent-assisted open-source
engineering. Implementation work is decomposed into explicit GitHub issue
contracts, reviewed, tested, and integrated through reproducible CI rather than
treating generated code as trusted by default.

## System overview

The target architecture separates **field work** from **administration**:

```text
134.2 kHz RFID
      ↓
ESP32 feeding-station node
      ↓  BLE, works without Internet
Field PWA
  login / sync / nodes / cats
      ↓  HTTP when connectivity is available
Spring Boot backend
      ↓
immutable RawObservations
      ↓
server-side DerivedVisits
      ↓
Admin web UI
  members / sites / cats / observations / visits
```

The field PWA remains installable and offline-capable. Administrative workflows
use a server-rendered Spring MVC/Thymeleaf back office so that the
field application stays focused on work at feeding stations.

## Current status

| Area | Current state |
| --- | --- |
| Repository architecture | React/TypeScript field PWA, Java/Spring Boot backend, PostgreSQL, C++20 firmware core, ESP32 composition layer, simulator, BLE protocol contracts |
| Offline collection | Local PWA outbox, offline-capable app shell, signed time-limited BLE credentials, AppDevice identities |
| Organizations & security | Multi-organization users/memberships, session login, tenant separation, signed offline authorization, node identity and claiming |
| Observation pipeline | Idempotent immutable raw-observation ingest and reproducible server-side visit derivation |
| Testing | Backend/app/firmware-core tests, PostgreSQL CI, Playwright flows, deterministic firmware simulator |
| Admin/UI split | Field PWA / server-rendered Admin separation complete (#30–#38) |
| Hardware | Firmware abstractions exist; physical RFID reader/antenna and long-running field validation are still outstanding |
| Deployment | First real-world shelter handoff is planned after hardware validation; broader reuse by other animal-welfare organizations is a project goal |

## Development priorities

The near-term path is deliberately practical:

1. validate the separated field-PWA / admin-backend workflows in real field use (#30–#38);
2. validate the physical 134.2 kHz RFID reader and antenna geometry (#3/#4);
3. prove the end-to-end path from chip read to derived visit (#13);
4. validate real-world power/runtime behavior (#12);
5. complete the remaining onboarding/invite flow (#19);
6. package the system so another shelter or organization can deploy it without
   project-specific knowledge.

Contributions are welcome, particularly around embedded hardware, RFID/antenna
testing, Web Bluetooth, Spring/Java, React/PWA, security review, deployment,
documentation, and field usability.

## Repository

| Pfad | Verantwortung |
| --- | --- |
| `/app` | React/TypeScript/Vite Feld-PWA: Login, Offline-Sync, Nodes und Katzen; Dexie, Web-Bluetooth-/Browser-Ports |
| `/backend` | Java/Spring Boot, Security, JPA, PostgreSQL, Flyway, HTTP OpenAPI und serverseitiges Admin-Backoffice |
| `/firmware-core` | Portabler C++20-Core und Hardware-Ports |
| `/firmware-esp32` | Plattform-Komposition; spätere ESP-IDF-Adapter |
| `/simulator` | Deterministischer Firmware-Core-Simulator und Szenario-Engine (`simulator/README.md`) |
| `/protocol` | BLE-Vertragsgrenze; getrennt vom HTTP-Vertrag |
| `/docs` | Domänenmodell, Trust Boundaries, ADRs, Abnahme |
| `/hardware` | Platz für Schaltplan, BOM, Verdrahtung und Messungen |

[Architektur](docs/architecture.md) und [ADRs](docs/adr/) sind die gemeinsame
Grundlage für folgende Implementierungen. Es gibt keine native Android-App; die
Feldanwendung bleibt bewusst eine installierbare PWA.

## Voraussetzungen

- Git, JDK **21 oder neuer** (CI: 21), `JAVA_HOME` gesetzt.
- Node.js **24 LTS, mindestens 24.15.0**, mit npm. `npm ci` nutzt das eingecheckte Lockfile.
- CMake ≥3.22 und ein C++20-Compiler (GCC/Clang oder MSVC mit C++-Build-Tools).
- Docker mit Compose für PostgreSQL **18.6**, alternativ ein lokaler PostgreSQL-Server.
- Netzwerk beim ersten Dependency-Download. Maven 3.9.11 lädt der eingecheckte,
  mit SHA-256 abgesicherte Wrapper; keine globale Maven-Installation erforderlich.
- Für Browser-Tests: Playwright Chromium (Installation unten). Physische Hardware
  und ESP-IDF sind für diese Foundation-Builds nicht erforderlich.

Shell-Beispiele verwenden POSIX. Unter Windows PowerShell `./mvnw` durch
`.\mvnw.cmd` ersetzen; npm-/CMake-Kommandos sind gleich. PowerShell-Variablen:
`$env:DB_URL = 'jdbc:postgresql://localhost:5432/miezmerker'` usw.

## Lokaler Start

Aus dem Repo-Root PostgreSQL starten:

```sh
docker compose up -d --wait postgres
```

Terminal 1:

```sh
cd backend
COOKIE_SECURE=false ./mvnw spring-boot:run
```

Terminal 2:

```sh
cd app
npm ci
npm run dev
```

PWA: `http://127.0.0.1:5173/sync`. Vite leitet `/api` und `/admin/**` an
`http://127.0.0.1:8080` weiter. Der Browser nutzt dieselbe Origin; CORS ist nicht
nötig. Health: `/api/v1/health`, Version: `/api/v1/version`, OpenAPI:
`/api/v1/openapi` (auch über den PWA-Proxy erreichbar). Health ist ein Liveness-
Signal; die Datenbank wird beim Start initialisiert, nicht bei jedem Health-Aufruf
erneut geprüft. Alle anderen Backend-Pfade erfordern eine authentifizierte Session.

Bootstrap (Versuchsphase, #16): beim ersten Start mit leerer Datenbank wird aus
`BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` eine erste Organisation mit einem
ADMIN angelegt (Passwort nur als BCrypt-Hash). Siehe
[docs/bootstrap.md](docs/bootstrap.md).

DB-Konfiguration: `DB_URL`, `DB_USER`, `DB_PASSWORD`; Backend-Port: `PORT`.
Die Compose-Zugangsdaten gelten nur für die lokale Entwicklung. Bei einem anderen
Backend-Port auch den Proxy in `app/vite.config.ts` anpassen. Beenden mit Ctrl+C;
`docker compose down` erhält die lokalen Daten im Volume.

## OpenAPI und generierter Client

Backend-DTOs sind die einzige HTTP-Schemaquelle. Export erfolgt gegen einen real
gestarteten Testserver, nicht aus einer handgeschriebenen Frontend-Spezifikation:

```sh
cd backend
./mvnw verify
cd ../app
npm ci
npm run api:generate
npm run api:check
```

`backend/target/openapi.json` ist der frische Export. Der Generator sortiert
Objekt-Schlüssel und schreibt `backend/openapi/v1.json` sowie
`app/src/api/generated.ts`. Der Runtime-Client in `app/src/api/client.ts` nutzt
`openapi-fetch` mit diesen generierten Typen und relativen URLs. Die generierten
Dateien einchecken; Backend-DTOs nicht manuell in TypeScript nachbauen. CI prüft
beide Dateien auf Drift und wiederholt die Prüfung. Breaking Changes brauchen
eine API-Versions-/Migrationsentscheidung. BLE/GATT gehört separat in `/protocol`.

## Builds und Tests

Core, Simulator und ESP32-Kompositionsgrenze:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel
ctest --test-dir build -C Release --output-on-failure
```

Bei Ninja/GCC/Clang gilt dasselbe; bei MSVC muss die C++-Toolchain installiert
sein. Der ESP32-Scaffold ist host-buildbar, noch kein flashbares Firmware-Image.

Backend:

```sh
cd backend
./mvnw verify
```

Ohne DB-Variablen verwenden Tests H2 als Test-Fallback. PostgreSQL wird in CI mit
denselben Integrationstests geprüft. Lokal mit laufendem Compose-PostgreSQL:

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5432/miezmerker \
TEST_DB_USER=miezmerker TEST_DB_PASSWORD=miezmerker ./mvnw test
```

PowerShell: die drei `TEST_DB_*`-Variablen über `$env:` setzen, dann
`.\mvnw.cmd test` ausführen. Flyway läuft in beiden Testvarianten; JPA validiert.

PWA (nach Backend-Export, während das Backend für den Browser-Test läuft):

```sh
cd app
npm ci
npm run api:check
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Linux benötigt für Chromium ggf. `npx playwright install --with-deps chromium`.
Playwright startet selbst die Production-Preview auf Port 4173 und den
Dev-Server auf Port 5173; beide Ports freihalten. Geprüft werden echter Same-Origin-Proxy, Offline-Neuladen der
Collector-App-Shell und persistente Outbox sowie Nodes/Cats, die Auth-Grenze und servergerenderte Admin-URLs
bei Desktop- und Mobilbreite. Der Vite-Dev-Modus aktiviert keinen
Service Worker. Zum manuellen Offline-Test `npm run build` und `npm run preview`
verwenden, `/sync` einmal online öffnen, Worker-Aktivierung abwarten, dann offline
neu laden. Verwaltung muss nicht vollständig offline gespiegelt werden.

Die CI in `.github/workflows/ci.yml` führt diese Builds/Tests ohne Hardware aus,
zusätzlich gegen PostgreSQL und mit dem gepackten Backend-JAR.

## Umfang des Fundaments und von #16–#18

Vorhanden: Shell-Routen, installierbare/offline-fähige Assets, lokale Dexie-Outbox,
explizite NodeTransport-/AppDeviceKeys-Ports, unabhängige Sync-Zustandstypen,
System-Endpunkte, HTTP-Client-Generierung und gemeinsame Core-Komposition.

Implementiert (#16–#18): Organisationen/User/Memberships mit serverseitiger
Mandantentrennung, Session-Login mit BCrypt + CSRF, Test-Bootstrap ohne Secrets im
Repo, AppDevice-Registrierung über Web Crypto, backendsignierte zeitlich begrenzte
Offline-Credentials (JWS/ES256, ADR-0012) mit Challenge/Response-Proof-of-Possession,
Node-Identität (UUIDv4 + P-256-Keypair) mit Claim-Modus-Port, atomicem Claiming und
idempotentem Retry, öffentliche Owner-Metadaten für fremde Organisationen, sowie
determinische Interop-Vektoren unter `protocol/fixtures/`.

Implementiert (#9/#10/#11): organisationsgebundene Stammdaten, idempotenter
Upload unveränderter Rohbeobachtungen, historisierte Node-Zuordnungen,
Backend-Visit-Ableitung und ursprünglich kleine Verwaltungsseiten in derselben PWA.
Seit #30–#38 bleiben dort Login, `/sync`, `/nodes` und `/cats`; die übrige
Verwaltung liegt unter `/admin/**` in Spring MVC/Thymeleaf.
Katzen bleiben bewusst in beiden Oberflächen. Nodes nutzt weiterhin
Futterstellenkontext; Futterstellen-Stammdatenpflege ist Admin-UI.
[Endgültige Aufteilung und Routing](docs/issue38-field-pwa.md).
Seit #64 nutzt die Feld-PWA Home (`/`), Sync (`/sync`), Futterstellen
(`/feeding-sites`, vorläufiger Einstieg in die bestehende `/nodes`-Ansicht) und
Katzen (`/cats`). [Shell, Routen und Grafik-Austausch](docs/ui-ux/app-shell.md).
Bedienung und API-Ergänzungen: [docs/issue11-management.md](docs/issue11-management.md).
Die kompakte [Katzenaktivität pro Futterstelle (#54)](docs/issue54-site-cat-activity.md)
liefert letzte verlässliche Visit-Zeiten und getrennte Empfangsmetadaten für die spätere Feld-PWA.
Der gemergte Collector (#8) unterstützt Offline-BLE und getrennten Backend-Upload;
physische Abnahme und Board-Anbindung stehen weiter aus (siehe Collector-Dokumentation).

Offen: #19 (Invite-per-E-Mail), physische BLE-Validierung und Hardware-Adapter.
Kein gemeinsames Organisations-Secret; sofortige Offline-
Revocation gibt es bewusst nicht (ADR-0012).
