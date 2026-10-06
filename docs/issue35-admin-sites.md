# Futterstellenverwaltung im Admin-Backend (#35)

Vertrag: [Issue #35](https://github.com/Grodahn/MiezMerker/issues/35).
Parent-Epic #30, abhängig von #33 (Admin-Shell), wiederverwendet #9/#11
(Domain/Service/Tenantlogik). Verwandte PWA-Seite `/sites` bleibt bis #38
bestehen.

## Routen (server-rendered, kein HTTP-Selbstaufruf)

- `GET /admin/sites` – Liste aller Futterstellen der aktiven Admin-Org
  (Name, Ort/Label, Beschreibung, aktuell zugeordnete Node-Anzahl, Links).
- `GET /admin/sites/new` – Anlageformular.
- `POST /admin/sites` – Anlage (CSRF-pflichtig, PRG nach Detail).
- `GET /admin/sites/{siteId}` – Detail: Stammdaten, aktuell zugeordnete
  Nodes, Deployment-Historie, letzte Aktivität, zehn zuletzt empfangene
  Rohbeobachtungen.
- `GET /admin/sites/{siteId}/edit` – Bearbeitungsformular (vorgefüllt).
- `POST /admin/sites/{siteId}` – Speichern (CSRF-pflichtig, PRG nach Detail).

Keine Mutation per GET. Kein Löschen in der Admin-UI (REST-Delete bleibt
ADMIN-API, blockiert bei referenzierenden Deployments/Observations).

## Wiederverwendung statt Zweitlogik

`AdminSitesController → FeedingSiteService → Repositories/Domain`.

- `FeedingSiteService` wurde aus `FeedingSiteController` extrahiert
  (gleiche Validierung: Name 1..255 Pflicht, Beschreibung max 2000,
  Label max 255, Koordinaten nur als Paar, endlich, Lat −90..90,
  Lng −180..180; `blank → null`, Trimmen).
- REST (`/api/v1/organizations/{id}/feeding-sites`) delegiert an denselben
  Service: identischer Vertrag, kein OpenAPI-Drift, PWA `/sites` läuft
  unverändert auf denselben Daten.
- Detail liest zusätzlich `NodeDeploymentRepository`
  (`findByOrganizationIdAndFeedingSiteId`), `NodeRepository` und
  `RawObservationRepository` (10 neueste per `receivedAt`, plus Zählung) –
  keine neue Aggregation, keine Zeitkorrektur.

## Mandantentrennung

Jede Operation nutzt ausschließlich die serverseitige aktive Admin-Org
(`ADMIN_ORG_ID` via `AdminService.requireActiveOrg` → `TenantService.requireAdmin`).

- Keine Org-ID aus dem Browser wird als Berechtigung akzeptiert.
  Das Anlageformular enthält `formOrganizationId` ausschließlich als
  Stale-Form-Prüfung: nach einem Org-Wechsel wird ein altes Formular mit
  409 abgewiesen. Die Schreibberechtigung und Ziel-Org kommen weiterhin
  ausschließlich aus dem validierten Session-Kontext.
- Reads/Writes via `findByOrganizationId` / `findByIdAndOrganizationId`.
- Fremde IDs → 404 ohne Leak (kein Name, Ort, Beschreibung, Nodes,
  Observations, Deployments, keine Existenz-Orakel).
- Multi-Org-ADMIN: `/admin/org`-Wechsel schaltet A↔B ohne stale Daten.

`/admin/sites/**` bleibt ADMIN-only (zentraler #33-Gate in
`BoundaryConfiguration` + `requireAdmin`), auch wenn REST-Pflege aktuell
MEMBER erlaubt. REST-Berechtigungen wurden nicht abgeschwächt.

## Nodes, Deployments, Historie

Zeitbasiert wie #9: `[valid_from` inklusiv, `valid_until)` exklusiv,
`null` = offen. Aktuell = deckt `now` ab (wie PWA-`currentDeployment`).

- Aktuelle Nodes: offene/deckende Deployments dieser Site mit noch
  zugehörigem CLAIMED-Node derselben Org; angezeigt mit Firmware, Notiz und
  letztem Serverkontakt (`lastContactAt`, `null` = „Noch kein Kontakt“).
- Historie: alle Deployments dieser Site, neuestes zuerst, mit
  `valid_from`/`valid_until` („Ohne Enddatum“ bei `null`, auch bei einem
  zukünftigen Start; dies bedeutet nicht aktuell zugeordnet).
- Stammdaten-Edit ändert nur `feeding_sites`-Spalten. Deployments,
  frozen `feeding_site_id`/`deployment_id` auf RawObservations und
  `feeding_site_id` auf DerivedVisits bleiben unberührt.

Beispiel-Garantie:

```text
Node N: Jan–Mär → Site A, Apr–jetzt → Site B
```

Öffnen/Bearbeiten von Site B zieht Januar-Reads/Visits nicht nach Site B.
Regressionstests weisen nach, dass nach Edit beider Sites alte Deployments,
Observation- und Visit-Zuordnungen identisch bleiben.

## Letzte Aktivität (ehrlich, ohne Korrektur)

- Serverempfang (`receivedAt`): immer exakt, sortiert die 10er-Liste.
- Verlässliche Sichtung: nur `SYNCED`/`RTC_ONLY`/`KNOWN` mit
  `0 < observedAtMs < 9224318016000000` wird als Zeit dargestellt.
- `UNKNOWN`/fehlend → „Uhrzeit unbekannt“, unplausibel → „Ungültige Zeit
  (Rohwert: …)“. Keine Umdeutung, keine neue Clock-Logik (#11-Semantik).

## CSRF/UX

Alle POSTs CSRF-pflichtig (Thymeleaf-Token, Spring-Security-Enforcement);
ohne Token → 403 ohne Zustandsänderung (Tests für Anlage + Edit).
Validierungsfehler rendern das Formular mit 200 erneut, mit allen
eingegebenen Werten, deutschen Meldungen und zugänglichen Labels;
Tastaturfokus und responsive Tabellen via wiederverwendetem #33-Layout
(`head`, `topbar`, `nav('sites')`, `footer`). Leere Org zeigt
„Noch keine Futterstellen vorhanden.“.

## Koexistenz mit der PWA (bis #38)

```text
PWA:     /sites  (bleibt, gleiche REST/Domain)
Backend: /admin/sites (neu, derselbe Service)
```

Keine zweite Fachlogik, keine Entfernung der PWA-Seite in #35.

## Tests

`AdminSitesTest` (16 Integrationstests, H2, wie `AdminShellTest`):

- Unauthentifiziert → Login-Redirect; ADMIN ok; MEMBER/PENDING/DISABLED → 403.
- Liste: nur eigene Sites, leere Org mit Empty-State.
- Anlage: ok + Org-Zugehörigkeit, Validierung (Name, Paar, Range) mit
  Werterhalt, CSRF-403.
- Detail: eigene Site mit Nodes/Deployments/Observations, ehrliche
  UNKNOWN-Darstellung.
- Edit: Masterdaten ok, Ownership/IDs stabil, Deployments/Observations/
  Visits unverändert, CSRF-403.
- Tenant-Isolation A↔B (Detail/Edit/Liste, kein Leak, Random-ID = 404).
- Historie A→B mit frozen Attribution + Visit-Garantie nach beidseitigem Edit.
- Multi-Org-Wechsel A↔B ohne stale Daten.
- Altes Anlageformular nach Org-Wechsel → 409 ohne Anlage in A oder B;
  frisch geöffnetes Formular legt korrekt in B an.
- Zukünftige offene Deployments werden nicht als aktuell bezeichnet;
  Tabellenstile bleiben auch ohne aktuelle Nodes erhalten.
- REST-Vertrag für PWA unverändert (JSON-Liste enthält erstellte Site).

Bestand: `AdminShellTest`, `Issue9IngestTest`, `Issue10VisitsTest`,
`TenancyAuthTest` u. a. laufen unverändert; kein OpenAPI-Drift
(REST-Schemas unverändert).
