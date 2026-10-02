# MiezMerker

MiezMerker unterstützt die Betreuung von Streunerkatzen an Futterstellen. Ein
batteriebetriebener ESP32-Node erkennt 134,2-kHz-Tierchips und sammelt Rohdaten.
Eine **gemeinsame installierbare PWA** übernimmt sie vor Ort per BLE und lädt sie
später ins Backend. Verwaltung und Auswertung gehören zu derselben PWA.

```text
RFID → Node (Rohdaten) → BLE → PWA (lokale Outbox) → HTTP Backend
                                                        ↓
                                          serverseitig abgeleitete Besuche
                                                        ↓
                                             Verwaltung in derselben PWA
```

Node-Sync funktioniert ohne Internet; Backend-Sync ist ein unabhängiger Ablauf.
Rohbeobachtungen bleiben unverändert. Firmware und PWA aggregieren keine Visits.
Issue [#2](https://github.com/Grodahn/MiezMerker/issues/2) liefert das Fundament.
Organisationen, Benutzer, Memberships und Login (#16), signierte Offline-BLE-
Berechtigungen mit AppDevice-Identitäten (#17) sowie Node-Identität und Claiming (#18)
sind implementiert. #19 (Invite-per-E-Mail) und die übrigen Features folgen.

## Repository

| Pfad | Verantwortung |
| --- | --- |
| `/app` | Ein React/TypeScript/Vite-Projekt: Collector #8 und Verwaltung #11; PWA, Dexie, Browser-Ports |
| `/backend` | Java/Spring Boot, Security, JPA, PostgreSQL, Flyway und HTTP OpenAPI |
| `/firmware-core` | Portabler C++20-Core und Hardware-Ports |
| `/firmware-esp32` | Plattform-Komposition; spätere ESP-IDF-Adapter |
| `/simulator` | Nutzt denselben Core mit simulierten Ports |
| `/protocol` | BLE-Vertragsgrenze; getrennt vom HTTP-Vertrag |
| `/docs` | Domänenmodell, Trust Boundaries, ADRs, Abnahme |
| `/hardware` | Platz für Schaltplan, BOM, Verdrahtung und Messungen |

[Architektur](docs/architecture.md) und [ADRs](docs/adr/) sind die gemeinsame
Grundlage für folgende Implementierungen. Es gibt keine native Android-App.

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

PWA: `http://127.0.0.1:5173/sync`. Vite leitet `/api` an
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
Playwright startet selbst die Production-Preview auf Port 4173; diesen Port
freihalten. Geprüft werden echter Same-Origin-Proxy, Offline-Neuladen der
Collector-App-Shell und persistente Outbox. Der Vite-Dev-Modus aktiviert keinen
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

Offen: #19 (Invite-per-E-Mail), BLE-Transport/GATT-Codec (#6), Collector (#8),
Rohdaten-Ingest (#9), Verwaltungsoberflächen (#11), echte Uploads, Visit-Ableitung
und Hardware-Adapter. Kein gemeinsames Organisations-Secret; sofortige Offline-
Revocation gibt es bewusst nicht (ADR-0012).
