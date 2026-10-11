# Organisationsgebundene Katzenfotos (#95)

Katzenfotos gehören zu einem organisationslokalen Cat-Profil, nie zu einer
globalen Chipidentität. Derselbe Chip darf in Organisation A „Minka“ mit
Foto A und in Organisation B „Schwarzi“ mit Foto B heißen; beide Fotos
sind vollständig unabhängig. Es gibt keine globale Katzenidentität und
keine globale Fotobibliothek.

## Speicherarchitektur

- **Metadaten:** Tabelle `cat_photos` (`V17__issue95_cat_photos.sql`), ein
  Datensatz je organisationslokaler Katze (`cat_id` als Primärschlüssel mit
  `ON DELETE CASCADE`, zusätzlich `organization_id` für mandantensichere
  Abfragen). Gespeichert werden nur Anzeigemetadaten (Größe, Breite, Höhe,
  Zeitstempel); interne Speicherschlüssel erscheinen nie in API-Antworten.
- **Dateien:** reines Dateisystemverzeichnis, konfigurierbar über
  `miezmerker.media.storage-dir` (Standard `data/cat-photos`,
  Umgebungsvariable `MEDIA_STORAGE_DIR`). Dateinamen sind zufällige,
  nicht erratbare UUIDs mit Endung `.jpg`; hochgeladene Dateinamen, Chip-IDs
  und Katzen-IDs fließen nie in Pfade ein. Das Verzeichnis liegt
  absichtlich außerhalb der öffentlich ausgelieferten statischen Ressourcen:
  Es gibt keine direkten öffentlichen Datei-URLs; jede Auslieferung läuft
  über autorisierungsgeprüfte Endpunkte.
- **Keine Cloudpflicht:** nur JDK-Bordmittel (`ImageIO`), keine nativen
  Bibliotheken, keine externen Dienste.

## Validierung und Normalisierung

Jede hochgeladene Datei gilt als nicht vertrauenswürdig:

- Erlaubt sind nur JPEG und PNG (Magic-Bytes + erfolgreiche Dekodierung);
  SVG, HTML, Text und alle anderen Formate werden abgewiesen.
- Größen- und Dimensionsgrenzen (`max-upload-bytes` Standard 5 MiB,
  `max-dimension` 4096, `max-pixels` 12 MP) schützen vor übergroßen Dateien
  und Dekompressionsbomben; Überschreitungen scheitern mit 400/413.
  Die Servlet-Multipart-Grenzen (`MEDIA_MULTIPART_MAX_FILE_SIZE` 6 MiB,
  `MEDIA_MULTIPART_MAX_REQUEST_SIZE` 10 MiB) liegen absichtlich knapp
  darüber, damit die Anwendung selbst mit kontrolliertem 413 antwortet.
- Jedes angenommene Bild wird neu gerendert: lange Kante maximal
  `target-long-edge` (Standard 1600 px, mobiltauglich), Neukodierung als
  JPEG mit `jpeg-quality` (Standard 0.85). Dabei entfallen EXIF, GPS,
  Thumbnails, Kommentare und Farbprofile — ein frisches `IIOImage` ohne
  Metadaten wird geschrieben.
- Pfadtraversal ist ausgeschlossen (feste UUID-Dateinamen, Prüfung auf
  `/`, `\`, `..`); Antworten enthalten nie absolute Pfade, Speicherschlüssel
  oder Stacktraces.

## API-Endpunkte

Eigene Organisation (aktive Mitgliedschaft im Pfad-Kontext erforderlich,
SYSADMIN allein genügt nicht):

- `PUT /api/v1/organizations/{org}/cats/{cat}/photo` (multipart, Feld
  `file`) — Upload/Ersetzen, auch für `MEMBER` (wie Katzenbearbeitung).
- `GET /api/v1/organizations/{org}/cats/{cat}/photo` — JPEG-Bytes,
  `Cache-Control: private, max-age=60, must-revalidate` + `ETag`.
- `DELETE /api/v1/organizations/{org}/cats/{cat}/photo` — Entfernen (204).
- `CatView` (`GET/POST/PATCH …/cats`) enthält `hasPhoto`/`photoUpdatedAt`
  zur Anzeigeentscheidung; unbekannte/fremde Katzen liefern 404/403 ohne
  unterscheidbare Details.

Fremde Organisation (jede Abfrage prüft neu; CARE allein genügt nie):

- `POST /api/v1/organizations/{org}/shared-care/photo`
  `{ownObservationRef, sourceOrganizationId}` — liefert das Foto der
  Quellorganisation für den serverseitig aus der Beobachtung aufgelösten
  Chip, nur bei wirksamem CARE **und** PHOTO, aktiver/sichtbarer
  Eigentümer- und Empfängerorganisation. `Cache-Control: no-store`.
- Ungültige/gefälschte Beobachtungsnachweise: 400. Fehlende Freigabe,
  fehlendes Foto, versteckte/deaktivierte/widerrufene Organisationen: in
  allen Fällen dasselbe 404 ohne Detail-Leak (Existenz nicht aufzählbar).

## Hochladen, Ersetzen, Löschen

- Validierung und Normalisierung laufen vollständig **vor** jeder
  Datei-/Metadatenmutation: Ein fehlgeschlagener Upload ersetzt oder
  zerstört das aktuelle Foto nie.
- Ersetzen schreibt die neuen Bytes unter neuem Zufallsnamen, aktualisiert
  die Metadaten in derselben Transaktion (Zeilen-Sperre gegen parallele
  Ersetzungen/Löschungen) und löscht die alte Datei erst nach Commit.
- Löschen entfernt die Metadaten in der Transaktion und die Datei nach
  Commit. Das Löschen einer Katze entfernt ihr Foto mit (Metadaten per
  `ON DELETE CASCADE`, Bytes explizit im gleichen Mandantenkontext).
- Schlägt das Löschen einer Datei fehl, wird nur protokolliert (kein Pfad
  in Antworten); verwaiste Dateien enthalten keine Personen- oder
  Organisationsbezüge (zufällige Namen, normalisierte JPEGs) und können vom
  Betreiber anhand fehlender `storage_key`-Referenzen erkannt werden.

## Backup, Retention, Cleanup

- **Backup:** Speicherverzeichnis und Datenbank gehören zusammen. Ohne
  Datenbank sind Dateien nicht zuordenbar (nur Zufallsnamen); ohne Dateien
  liefern vorhandene Metadaten 404. Beides gemeinsam sichern und
  wiederherstellen.
- **Retention:** Fotos leben genau so lange wie ihr Katzenprofil. Widerruf
  einer PHOTO-Freigabe blockiert sofort alle neuen Backend-Lesezugriffe;
  bereits heruntergeladene Kopien auf fremden Geräten können nicht
  ferngelöscht werden (dies wird auch in der UI so kommuniziert).
- **Cleanup:** Normale Upload-/Ersetzungs-/Löschpfade hinterlassen keine
  Waisen (`upload-*.tmp`-Reste nach Absturz kann der Betreiber löschen).
  Nach Wiederherstellung aus getrennten Backups verwaiste Dateien sind
  plain-JPEGs ohne Metadaten und gefahrlos löschbar, sobald kein
  `cat_photos.storage_key` mehr auf sie zeigt.

## Caching und Widerruf (Client)

- Geteilte Fotos sind strikt online-only: `Cache-Control: no-store`,
  keine Ablage in Dexie/IndexedDB/localStorage/Cache Storage/Service
  Worker, keine Einbettung in die Offline-Asset-Registry, nur transiente
  Blob-URLs, die bei Logout, Organisationswechsel, Berechtigungsverlust,
  Navigation und fehlgeschlagenen Neuprüfungen widerrufen und aus dem DOM
  entfernt werden. Abgebrochene/veraltete Antworten stellen nach einem
  Kontextwechsel kein Foto wieder her.
- Eigene Fotos nutzen `private`-Caching mit `ETag`-Revalidierung und
  werden ebenfalls aus dem transienten Speicher entfernt, sobald der
  Organisationskontext wechselt oder die Sitzung endet.
