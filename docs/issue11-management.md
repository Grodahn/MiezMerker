# Kleine Verwaltungs- und Beobachtungsoberfläche (#11)

Vertrag: [Issue #11](https://github.com/Grodahn/MiezMerker/issues/11), vor der
Implementierung am 2026-10-05 von GitHub gelesen. Ausgangspunkt ist `main`
(`af9f3f3`) mit den gemergten PRs #20 (#2), #23 (#16), #25 (#9), #26 (#8)
und #28 (#10).

## Bedienung in der gemeinsamen PWA

Die vorhandene App-Shell, Anmeldung und Organisationsauswahl werden weiterverwendet.
Alle Seiten gehören zur einzigen PWA unter `/app`:

- `/sites`: Liste, Anlage und Detailpflege von Name, Beschreibung und Standort;
  aktuell zugeordnete Nodes und zehn zuletzt empfangene Rohbeobachtungen.
- `/nodes`: Identität, Versionen, letzter bekannter Serverkontakt, technische
  Notiz, zeitlich gültige Zuordnung und vollständige Deployment-Historie.
  Neue Hardware-Nodes entstehen über das bestehende ADMIN-Claiming unter `/sync`.
- `/cats`: gepflegte Katzen und noch nicht zugeordnete beobachtete Chips,
  optionaler Name/Status/Notiz, letzte verlässliche Sichtung, getrennter letzter
  Serverempfang, Clock-Hinweise, historisch bekannte Futterstellen und paginierte Visits.
- `/observations`: paginierte unveränderte Reads, Filter nach Futterstelle,
  Node, Chip und Zeitraum; Sequence, RTC-Rohwert, Clock-Status, Monotonzeit,
  Boot-Counter und Deployment in technischen Details. Unbekannte Chips können
  direkt als Katze angelegt werden.
- `/visits`: getrennte abgeleitete Besuche mit Start/Ende, Dauer, Read-Anzahl,
  Futterstelle, Katze/Chip, Algorithmusversion, Gap und erster/letzter Rohbeobachtung.
  ADMIN kann mit den bestehenden Backend-Standardparametern neu berechnen.
- `/admin/members`: ADMIN sieht die vorhandene Mitgliederliste. Keine
  E-Mail-Einladungen oder Erweiterung von #19.

Zeitfilter verwenden lokale Browsereingaben, übertragen Epoch-Millisekunden
und behandeln das Ende exklusiv. Bei Visits wird der Beginn gefiltert, bei
Rohbeobachtungen der RTC-Rohwert. Reads ohne RTC-Zeit sind ohne Zeitfilter sichtbar.
Es gibt keine Zeitkorrektur, Futterstellen-Neuzuordnung oder Visit-Aggregation
im Frontend. 64-Bit-Rohwerte bleiben Strings; die Besuchsdauer verwendet BigInt.

## Kleine API-Ergänzungen

OpenAPI bleibt verbindlich; `app/src/api/generated.ts` wird aus dem frisch
exportierten Backend-Vertrag regeneriert. Die UI verwendet ausschließlich den
bestehenden `openapi-fetch`-Client und dessen generierte Schemas.

1. **Routinepflege für ACTIVE MEMBER:** Anlage/Bearbeitung von Futterstellen und
   Katzen, Node-Metadaten und Deployment-Pflege verwenden `requireActive` statt
   `requireAdmin`, entsprechend #11. Löschung, Claiming, Mitgliedsadministration
   und Visit-Recompute bleiben ADMIN vorbehalten. Tenant-Gates bleiben serverseitig.
2. **Atomarer Umzug:** `POST /api/v1/organizations/{organizationId}/deployments/move`
   mit `{nodeId, feedingSiteId, validFrom}`. Prüft die Membership, Node und
   Zielfutterstelle im selben Tenant und sperrt den Node. Schließt das bisher
   offene Intervall und legt das neue in einer Transaktion an. Ohne offene
   Zuordnung entsteht die erste. Überlappung, gleicher Standort oder Umzug vor/am
   Start des offenen Intervalls liefern 409. Fehler hinterlassen kein halb
   geschlossenes Deployment; historische RawObservation-/Visit-Zuordnungen bleiben
   unverändert. Bestehende Create/Close-Endpunkte bleiben vorhanden.
3. **Chip-Aktivität:** `GET /api/v1/organizations/{organizationId}/chip-activity`
   gruppiert Rohbeobachtungen serverseitig pro Chip, einschließlich Chips ohne
   Cat. Liefert das Maximum der verlässlichen Sichtungszeit als Dezimalstring,
   separat letzten Serverempfang, Anzahl Reads mit unsicherer Uhr und historisch
   eingefrorene Futterstellen-IDs. UNKNOWN/fehlende/unplausible Zeiten werden nicht
   als genaue Sichtung dargestellt. Die Summary vermeidet das Herunterladen aller
   Rohdaten für diese Anzeige. Keine neue Persistenz oder Migration nötig.
4. **Letzter Empfang:** optionales `newestFirst=true` für `GET /api/v1/observations`.
   Kehrt die bestehende stabile Sortierung nach Empfang/Sequence/ID um. Der
   bisherige Default und die vorhandenen Filter/Pagination bleiben unverändert.

## Organisationswechsel und Offline-Grenze

Verwaltungsdaten bleiben im Arbeitsspeicher des aktuellen Views. Keine Spiegelung
in Dexie, LocalStorage oder Cache Storage; Requests verwenden `cache: no-store`,
Spring Security liefert private API-Antworten mit `Cache-Control: no-store`.
Die bestehenden Service-Worker-Regeln werden nicht verändert.

Wechsel von Account, aktiver Organisation oder Rolle verwirft den View und
bricht seine Requests ab. Eine Generation verhindert auch bei gebatchtem
A → B → A, dass alte Antworten erneut sichtbar werden. Laufende Mutationen
prüfen den Kontext erneut nach CSRF-Auflösung. Fehlende/abgelaufene Session,
inaktive Membership, Serverfehler und Konflikte werden mit neutralen Hinweisen
angezeigt; Backend-Details mit Chip-/Standortdaten landen nicht in UI-Logs.
Chip- und Standortfilter sind View-State und werden nicht in Navigations-URLs geschrieben.
Beim Verlassen eines Verwaltungsdokuments (`pagehide`) werden Datensätze und
Entwürfe synchron verworfen und laufende Requests abgebrochen. Stellt der Browser
es aus dem Back/Forward-Cache wieder her (`pageshow.persisted`), lädt es die Seite
neu und verwendet die bestehende Session-/Organisationswiederherstellung. Alte
Verwaltungsdaten erscheinen so nicht nach einem Wechsel in einem anderen Dokument.

Offline öffnet die Verwaltung mit einem Verbindungshinweis und Link auf `/sync`.
Collector, AppDevice, Offline-Credentials, BLE, Outbox und Upload-Zustandsmaschinen
bleiben unverändert. Tabellen sind horizontal scrollbar, Formulare passen sich
mobil an; Controls haben Labels und Tastaturfokus.

## Prüfungen

- Frontend: leere Installation auf allen fünf Routen, MEMBER-Pflege,
  atomare Node-Zuordnung, unbekannter Chip → benannte Katze, alle Raw-Filter und
  Pagination, getrennte Visits/Version/Dauer, letzte Sichtung, Context-Abbruch,
  A → B → A, Wechsel während CSRF, History-Lifecycle, ADMIN/MEMBER, Offline und 401/Retry.
- Backend: vorhandene #9/#10- und Auth-Negativtests; neue Fälle für MEMBER-Pflege,
  atomaren Umzug mit alten und verspäteten Reads/Visits, gescheiterte und
  konkurrierende Umzüge, fremde IDs, Chip-Aktivität, unbekannte Clock, no-store
  und rückwärts paginierten Empfang.
- Chromium: Verwaltungsablauf über den echten generierten Client mit kontrollierten
  API-Antworten bei 1280 px und 390 px; Organisationswechsel, historische Anzeige,
  ADMIN-Gate, Rückkehr zum Collector und keine API-Antworten in Cache Storage.
  Die echte Backend-Autorisierung wird durch die HTTP-Integrationstests geprüft.
- Bestehender Browsernachweis: Offline-Collector/IndexedDB und echter Same-Origin-Proxy.

Die verbleibende physische BLE-Abnahme von #8 ist weiterhin in
`docs/collector-validation.md` dokumentiert und wird durch #11 nicht ersetzt.

Lokale Validierung am 2026-10-05: 108 Backend-HTTP-/Integrationsprüfungen unter H2,
189 Frontendprüfungen einschließlich Collector-Regressionen, TypeScript und
Vite-PWA-Produktionsbuild sowie reproduzierbare API-Driftprüfungen. Alle fünf
Chromium-Szenarien bestehen: Offline-Collector, echter Same-Origin-Proxy,
Verwaltungsablauf bei Desktop-/Mobilbreite und History-Lifecycle. Screenshots beider Breiten wurden
visuell geprüft. PostgreSQL wird weiterhin durch die vorhandene CI geprüft;
die lokale Backend-Prüfung verwendete den Test-Fallback H2.
