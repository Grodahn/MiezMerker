# Admin-Katzenverwaltung (#37)

Die servergerenderte Katzenverwaltung ist unter `/admin/cats` erreichbar.
Sie nutzt die Admin-Navigation, ACTIVE-ADMIN-Sicherheit und validierte aktive
Organisation aus #33. **PWA `/cats` bleibt vollständig erhalten.** Beide Oberflächen
verwenden dasselbe Cat-Modell und dieselben gemeinsamen Pflege-/Chip-Services.
REST-Verträge und die ACTIVE-MEMBER-Rechte der PWA ändern sich nicht.

## Anzeigen und pflegen

- `GET /admin/cats`: bekannte Katzen und separat beobachtete unbekannte Chips.
  Name, Chip-ID, Status, Notiz, letzte verlässliche Sichtung, letzte historisch
  bekannte Futterstelle, Beobachtungsanzahl, Uhrzeitprobleme und letzter Server-Empfang.
- `GET /admin/cats/{catId}`: Details, bekannte historische Futterstellen und
  bis zu zehn vorhandene DerivedVisits für den organisationsgebundenen Chip.
- `GET /admin/cats/{catId}/edit`, `POST /admin/cats/{catId}`: Name (255 Zeichen),
  Status (64), Notiz (2000), jeweils optional; leere Werte entfernen den Inhalt.
  Validierung verwendet die bestehenden API-Request-Constraints. Chip-ID und
  Organisationszugehörigkeit werden hier nicht verändert.
- `GET /admin/cats/from-chip?chipId=...`, `POST /admin/cats/from-chip`:
  Katze aus einem in der aktiven Organisation beobachteten, noch nicht zugeordneten
  Chip anlegen. Bestehende Normalisierung und organisationsbezogene Eindeutigkeit
  gelten. Unbekannte Chips sind ein gültiger Zustand, kein Fehler.

Alle Mutationen brauchen CSRF. Ein verstecktes `formOrganizationId` dient nur
als Schutz gegen alte Formulare nach einem Organisationswechsel; die serverseitig
validierte Session bleibt die einzige Tenant-Autorität. Fremde Katzen/Chips
liefern neutral 404. Für denselben Chip in A und B gelten getrennte Zuordnungen.

## Zeit und historische Futterstellen

Sichtung und Server-Empfang werden getrennt als UTC/ISO-Zeit angezeigt.
Die gemeinsame #11-Abfrage wertet nur SYNCED/RTC_ONLY/KNOWN mit gültigem,
positivem Timestamp als verlässliche Sichtung. UNKNOWN oder ungültige Zeiten
werden ausdrücklich als unsicher gezählt; ohne verlässliche Beobachtung steht
„unbekannt – keine verlässliche Uhrzeit“. `received_at` ersetzt niemals die Sichtung.
Es erfolgt keine Uhrzeitkorrektur.

Futterstellen stammen aus den bei Ingest gespeicherten `feeding_site_id`-Werten.
Ein Node-Umzug schreibt alte Zuordnungen nicht um. Die letzte bekannte
Futterstelle gehört zur letzten verlässlichen Sichtung; ist deren historische
Zuordnung unbekannt, bleibt auch die Futterstelle unbekannt. Gleichzeitige
Sichtungen an mehreren Stellen zeigen alle zugehörigen Futterstellen.

Besuche werden ausschließlich aus vorhandenen DerivedVisits gelesen, anhand
Organisation und Chip statt eines möglicherweise veralteten Cat-Snapshots.
Ihre gespeicherte Futterstelle und Start-/Endzeiten bleiben erhalten. Kein
Controller berechnet Besuche neu. Allgemeine Beobachtungs-/Besuchsverwaltung
und Recompute gehören zu #36.

## Abfragen und Regressionen

Die Liste verwendet organisationsgebundene, gebündelte Abfragen für Katzen,
Chip-Aggregate, bekannte Futterstellen und letzte historische Futterstellen.
Keine vollständige Beobachtungshistorie pro Katze, keine Abfrage pro Tabellenzeile,
kein organisationsübergreifender Cache. Die Detail-Besuchsabfrage ist auf 10 begrenzt.

`AdminCatsTest` prüft echte HTTP-/Session-/CSRF-Flows, Zugang, Felder,
Validierung, unveränderliche Identität, fremde IDs/Chips, UNKNOWN,
verlässliche Sichtung, Node-Umzug, gespeicherte Visits und Organisationswechsel.
Die bestehende Backend-Suite und PWA-/API-Checks schützen #9–#11 und #33–#35.
