# MiezMerker

**[English version Below](#english-version)**

Hinweise zu gezielten Testbefehlen, kompakten Testprotokollen und der dreistufigen
Teststrategie stehen unter [Tests](docs/testing.md).

Ein explizites lokales Setup mit fünf benannten Katzen, einer Futterstelle und
Demo-SYSADMIN bietet [DEMO ONLY (#111)](docs/dev-demo.md).

**MiezMerker ist eine quelloffene Hardware- und Softwareplattform für Tierheime,
Tierschutzgruppen und Organisationen, die Streunerkatzen versorgen.** Mithilfe
kostengünstiger RFID-/ESP32-NapfNodes sollen Katzen an Futterstellen anhand ihres
Mikrochips erkannt, Beobachtungen vor Ort auch ohne Internet erfasst und daraus
nützliche Besuchsverläufe für die Tierschutzarbeit abgeleitet werden.

Das Projekt soll von interessierten Tierheimen und Organisationen frei genutzt
und selbst betrieben werden können, statt sie an einen proprietären Dienst zu
binden. Nach erfolgreicher Validierung der Hardware ist als erster praktischer
Einsatz die Übergabe an ein Tierheim in Düren geplant. Die mandantenfähige
Architektur ermöglicht es weiteren Tierheimen, dieselbe Software unabhängig
voneinander einzusetzen.

MiezMerker wird derzeit aktiv entwickelt und ist **noch kein fertiges System
zur Tierbeobachtung**. Große Teile der Softwaregrundlage sind bereits vorhanden;
die praktische Erprobung des RFID-Lesers und der Antenne sowie die abschließenden
Feldtests stehen noch aus.

## Warum dieses Projekt?

Die Versorgung von Streunerkatzen erfordert oft wiederholte manuelle
Beobachtungen: Welche Katze kommt zu welcher Futterstelle? Ist ein bekanntes
Tier zurückgekehrt? Taucht regelmäßig ein bislang unbekannter Mikrochip auf?

MiezMerker soll hierfür eine kostengünstige, offene Alternative bieten:

- vorhandene Tiermikrochips mit 134,2 kHz zur Identifizierung nutzen;
- Beobachtungen mit akkubetriebenen ESP32-NapfNodes an Futterstellen erfassen;
- das Auslesen vor Ort auch ohne Internetverbindung ermöglichen;
- Beobachtungen später über eine browserbasierte Feldanwendung synchronisieren;
- aus unverändert gespeicherten Rohbeobachtungen zentral Besuche ableiten;
- mehrere unabhängige Organisationen mit strikter Mandantentrennung unterstützen;
- Baupläne, Protokolle, Backend und Anwendung offen nachvollziehbar und
  reproduzierbar halten.

Das Projekt ist zugleich ein praktischer Versuch mit KI-Agenten unterstützter
Open-Source-Entwicklung. Die Umsetzung wird in klar abgegrenzte GitHub-Issues
aufgeteilt, überprüft, getestet und über eine reproduzierbare CI integriert.
KI-generierter Code wird dabei nicht ungeprüft als korrekt vorausgesetzt.

## Systemübersicht

Die Zielarchitektur trennt die **Arbeit vor Ort** von der **Verwaltung**:

```text
134,2-kHz-RFID
      ↓
ESP32-NapfNode an der Futterstelle
      ↓  BLE, auch ohne Internet
Feld-PWA
  Login / Sync / Näpfe / Katzen
      ↓  HTTP, sobald eine Verbindung verfügbar ist
Spring-Boot-Backend
      ↓
unveränderliche RawObservations
      ↓
serverseitig abgeleitete DerivedVisits
      ↓
Admin-Weboberfläche
  Mitglieder / Futterstellen / Katzen / Beobachtungen / Besuche
```

Die Feld-PWA bleibt installierbar und offlinefähig. Verwaltungsabläufe werden
über ein serverseitig gerendertes Spring-MVC-/Thymeleaf-Backoffice abgewickelt,
damit sich die Feldanwendung auf die Arbeit an Futterstellen konzentriert.

## Aktueller Stand

| Bereich | Stand |
| --- | --- |
| Repository-Architektur | Feld-PWA mit React/TypeScript, Backend mit Java/Spring Boot und PostgreSQL, C++20-Firmware-Kern, ESP32-Anbindung, Simulator und BLE-Protokollverträge |
| Offline-Erfassung | Lokale PWA-Outbox, offlinefähige App-Oberfläche, signierte zeitlich begrenzte BLE-Berechtigungen und AppDevice-Identitäten |
| Organisationen und Sicherheit | Benutzer und Mitgliedschaften für mehrere Organisationen, Session-Login, Mandantentrennung, signierte Offline-Autorisierung, Geräteidentitäten und Claiming |
| Beobachtungsverarbeitung | Idempotente Übernahme unveränderlicher Rohbeobachtungen und reproduzierbare serverseitige Ableitung von Besuchen |
| Tests | Backend-, App- und Firmware-Kern-Tests, PostgreSQL in der CI, Playwright-Abläufe und deterministischer Firmware-Simulator |
| Trennung von Verwaltung und Feldanwendung | Feld-PWA und serverseitig gerenderte Admin-Oberfläche getrennt (#30–#38) |
| Hardware | Firmware-Abstraktionen vorhanden; praktische Tests des RFID-Lesers und der Antenne sowie längere Feldtests stehen noch aus |
| Bereitstellung | Ein erster Einsatz in einem Tierheim ist nach der Hardwarevalidierung geplant; weitere Tierschutzorganisationen sollen das System ebenfalls nutzen können |

## Nächste Entwicklungsschritte

Die nächsten Schritte sind bewusst praxisorientiert:

1. die getrennten Abläufe von Feld-PWA und Admin-Backend im praktischen Einsatz
   überprüfen (#30–#38);
2. den physischen RFID-Leser für 134,2 kHz und die Antennengeometrie erproben (#3/#4);
3. die gesamte Verarbeitung vom Chip-Lesevorgang bis zum abgeleiteten Besuch
   nachweisen (#13);
4. Stromverbrauch und tatsächliche Laufzeit im Einsatz prüfen (#12);
5. den noch offenen Einladungs- und Registrierungsablauf vervollständigen (#19);
6. das System so paketieren, dass andere Tierheime und Organisationen es ohne
   projektspezifisches Vorwissen installieren und betreiben können.

Beiträge sind willkommen, insbesondere bei Embedded-Hardware, RFID- und
Antennentests, Web Bluetooth, Spring/Java, React/PWA, Sicherheitsprüfungen,
Bereitstellung, Dokumentation und Bedienbarkeit im Feldeinsatz.

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
- Netzwerkzugang beim ersten Herunterladen der Abhängigkeiten. Maven 3.9.11 lädt der eingecheckte,
  mit SHA-256 abgesicherte Wrapper; keine globale Maven-Installation erforderlich.
- Für Browser-Tests: Playwright Chromium (Installation unten). Physische Hardware
  und ESP-IDF sind für diese grundlegenden Builds nicht erforderlich.

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
beide Dateien auf Drift und wiederholt die Prüfung. Inkompatible Änderungen brauchen
eine API-Versions-/Migrationsentscheidung. BLE/GATT gehört separat in `/protocol`.

## Builds und Tests

Core, Simulator und ESP32-Kompositionsgrenze:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel
ctest --test-dir build -C Release --output-on-failure
```

Bei Ninja/GCC/Clang gilt dasselbe; bei MSVC muss die C++-Toolchain installiert
sein. Das ESP32-Grundgerüst lässt sich auf dem Entwicklungsrechner bauen, ist aber noch kein flashbares Firmware-Image.

Backend:

```sh
cd backend
./mvnw verify
```

Ohne DB-Variablen verwenden Tests ersatzweise H2 als Testdatenbank. PostgreSQL wird in CI mit
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
Playwright startet selbst die Produktionsvorschau auf Port 4173 und den
Entwicklungsserver auf Port 5173; beide Ports freihalten. Geprüft werden echter Same-Origin-Proxy, Offline-Neuladen der
Collector-Oberfläche und persistente Outbox sowie Näpfe/Katzen, die Auth-Grenze und servergerenderte Admin-URLs
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
Node-Identität (UUIDv4 + P-256-Keypair) mit Claim-Modus-Port, atomarem Claiming und
idempotenten Wiederholungsversuchen, öffentliche Organisationsdaten für fremde Organisationen, sowie
deterministische Interoperabilitäts-Testvektoren unter `protocol/fixtures/`.

Implementiert (#9/#10/#11): organisationsgebundene Stammdaten, idempotenter
Upload unveränderter Rohbeobachtungen, historisierte Node-Zuordnungen,
Backend-Visit-Ableitung und ursprünglich kleine Verwaltungsseiten in derselben PWA.
Seit #30–#38 bleiben dort Login, `/sync`, `/nodes` und `/cats`; die übrige
Verwaltung liegt unter `/admin/**` in Spring MVC/Thymeleaf.
Katzen bleiben bewusst in beiden Oberflächen. Die Node-Ansicht nutzt weiterhin
den Futterstellenkontext; die Stammdatenpflege erfolgt in der Admin-Oberfläche.
[Endgültige Aufteilung und Routing](docs/issue38-field-pwa.md).
Seit #64 nutzt die Feld-PWA Home (`/`), Sync (`/sync`), Futterstellen
(`/feeding-sites`, vorläufiger Einstieg in die bestehende `/nodes`-Ansicht) und
Katzen (`/cats`). [Shell, Routen und Grafik-Austausch](docs/ui-ux/app-shell.md).
Bedienung und API-Ergänzungen: [docs/issue11-management.md](docs/issue11-management.md).
Die kompakte [Katzenaktivität pro Futterstelle (#54)](docs/issue54-site-cat-activity.md)
liefert letzte verlässliche Visit-Zeiten und getrennte Empfangsmetadaten für die spätere Feld-PWA.
Der gemergte Collector (#8) unterstützt Offline-BLE und getrennten Backend-Upload;
physische Abnahme und Board-Anbindung stehen weiter aus (siehe Collector-Dokumentation).

Offen: #19 (Einladungen per E-Mail), physische BLE-Validierung und Hardware-Adapter.
Kein gemeinsames Organisations-Secret; sofortige Offline-
Sperrung gibt es bewusst nicht (ADR-0012).

---

## English version

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

| Path | Responsibility |
| --- | --- |
| `/app` | React/TypeScript/Vite field PWA: login, offline sync, nodes and cats; Dexie and Web Bluetooth/browser ports |
| `/backend` | Java/Spring Boot, security, JPA, PostgreSQL, Flyway, HTTP OpenAPI, and server-side administration back office |
| `/firmware-core` | Portable C++20 core and hardware ports |
| `/firmware-esp32` | Platform composition; future ESP-IDF adapters |
| `/simulator` | Deterministic firmware-core simulator and scenario engine (`simulator/README.md`) |
| `/protocol` | BLE protocol boundary, separate from the HTTP contract |
| `/docs` | Domain model, trust boundaries, ADRs, acceptance documentation |
| `/hardware` | Space for circuit diagrams, bill of materials (BOM), wiring, and measurements |

[Architecture](docs/architecture.md) and [ADRs](docs/adr/) provide the shared
foundation for subsequent implementations. There is no native Android app;
the field application is deliberately an installable PWA.

## Prerequisites

- Git, JDK **21 or newer** (CI: 21), with `JAVA_HOME` set.
- Node.js **24 LTS, at least 24.15.0**, with npm. `npm ci` uses the committed lockfile.
- CMake ≥3.22 and a C++20 compiler (GCC/Clang or MSVC with C++ build tools).
- Docker with Compose for PostgreSQL **18.6**, or a local PostgreSQL server.
- Network access for the initial dependency download. The checked-in Maven
  Wrapper downloads Maven 3.9.11 and verifies its SHA-256 checksum; a
  system-wide Maven installation is not required.
- For browser testing: Playwright Chromium (installation below). Physical
  hardware and ESP-IDF are not required for these foundational builds.

Shell examples use POSIX syntax. On Windows PowerShell, replace `./mvnw` with
`.\mvnw.cmd`; npm/CMake commands remain the same. PowerShell variables:
`$env:DB_URL = 'jdbc:postgresql://localhost:5432/miezmerker'`, etc.

## Running locally

From the repository root, start PostgreSQL:

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

PWA: `http://127.0.0.1:5173/sync`. Vite proxies `/api` and `/admin/**` to
`http://127.0.0.1:8080`. The browser uses the same origin, so CORS is not
needed. Health: `/api/v1/health`; version: `/api/v1/version`; OpenAPI:
`/api/v1/openapi` (also accessible through the PWA proxy). The health endpoint
provides a liveness signal; the database is initialized on startup, not
checked again on every health request. All other backend routes require an
authenticated session.

Bootstrap (experimental phase, #16): on the first launch with an empty
database, `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` create the
first organization with an ADMIN user (password stored only as a BCrypt hash).
See [docs/bootstrap.md](docs/bootstrap.md).

Database configuration: `DB_URL`, `DB_USER`, `DB_PASSWORD`; backend port: `PORT`.
The Compose credentials are for local development only. If the backend port
changes, update the proxy in `app/vite.config.ts` as well. Stop with Ctrl+C;
`docker compose down` preserves local data in its volume.

## OpenAPI and generated client

Backend DTOs are the sole source of the HTTP schema. Export happens against a
running test server, not from a manually written frontend specification:

```sh
cd backend
./mvnw verify
cd ../app
npm ci
npm run api:generate
npm run api:check
```

`backend/target/openapi.json` is the fresh export. The generator sorts object
keys and writes `backend/openapi/v1.json` and `app/src/api/generated.ts`.
The runtime client in `app/src/api/client.ts` uses `openapi-fetch` with the
generated types and relative URLs. Commit the generated files; do not
reimplement backend DTOs manually in TypeScript. CI checks both generated
files for drift and repeats the verification. Breaking changes require
a decision about API versioning and migration. BLE/GATT is specified
separately in `/protocol`.

## Builds and tests

Core, simulator, and ESP32 composition boundary:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel
ctest --test-dir build -C Release --output-on-failure
```

The same applies with Ninja/GCC/Clang; for MSVC, the C++ toolchain must be
installed. The ESP32 scaffold can be built on the host but is not yet
a firmware image ready to flash.

Backend:

```sh
cd backend
./mvnw verify
```

Without database variables, tests use H2 as a fallback test database.
CI also runs the same integration tests against PostgreSQL. To test
locally with a running Compose PostgreSQL instance:

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5432/miezmerker \
TEST_DB_USER=miezmerker TEST_DB_PASSWORD=miezmerker ./mvnw test
```

PowerShell: set the three `TEST_DB_*` variables through `$env:`, then
run `.\mvnw.cmd test`. Flyway runs in both test configurations; JPA validates
the model.

PWA (after the backend export, while the backend is running for browser tests):

```sh
cd app
npm ci
npm run api:check
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Linux may require `npx playwright install --with-deps chromium`.
Playwright starts the production preview on port 4173 and the development
server on port 5173; keep both ports available. Tests cover the real
same-origin proxy, offline reloading of the collector shell and persistent
outbox, nodes/cats, authentication boundaries, and server-rendered admin
URLs at desktop and mobile widths. Vite development mode does not enable
a service worker. For a manual offline test, use `npm run build` and
`npm run preview`, open `/sync` once while online, wait for the service
worker to activate, then reload offline. Administration does not need
to be completely mirrored for offline use.

CI in `.github/workflows/ci.yml` runs these builds/tests without hardware,
also against PostgreSQL and using the packaged backend JAR.

## Scope of the foundation and #16–#18

Available foundation: shell routes, installable/offline-ready assets, local
Dexie outbox, explicit NodeTransport/AppDeviceKeys ports, independent sync
state types, system endpoints, HTTP client generation, and shared core
composition.

Implemented (#16–#18): organizations/users/memberships with server-side
tenant separation; session login with BCrypt and CSRF; test bootstrap without
secrets in the repository; AppDevice registration via Web Crypto; backend-signed
time-limited offline credentials (JWS/ES256, ADR-0012) with challenge/response
proof of possession; node identity (UUIDv4 + P-256 key pair), physical claim
mode port, atomic claiming and idempotent retries; public owner metadata for
other organizations; and deterministic interoperability vectors in
`protocol/fixtures/`.

Implemented (#9/#10/#11): organization-scoped master data, idempotent
upload of immutable raw observations, historical node deployments, backend
visit derivation, and initially small management views within the same PWA.
Since #30–#38, login, `/sync`, `/nodes`, and `/cats` remain in the field PWA;
other administration is under `/admin/**` using Spring MVC/Thymeleaf.
Cats deliberately remain visible in both interfaces. Nodes retain feeding
site context; feeding-site master data is managed in the admin UI.
[Final UI separation and routing](docs/issue38-field-pwa.md).

Since #64, the field PWA provides Home (`/`), Sync (`/sync`), Feeding Sites
(`/feeding-sites`, currently leading to the existing `/nodes` view),
and Cats (`/cats`). [App shell, routes, and interchangeable assets](docs/ui-ux/app-shell.md).
Usage details and API extensions: [docs/issue11-management.md](docs/issue11-management.md).
The compact [cat activity per feeding site (#54)](docs/issue54-site-cat-activity.md)
provides the most recent reliable visit times and separately tracked reception
metadata for the upcoming field-PWA display.
The merged collector (#8) supports offline BLE collection and separate
backend upload; physical acceptance tests and board integration are still
pending (see collector documentation).

Outstanding: #19 (email invitations), physical BLE validation, and hardware
adapters. No shared organization secret is distributed; immediate offline
revocation is deliberately unsupported (ADR-0012).
