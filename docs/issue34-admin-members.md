# Mitgliederverwaltung im Admin-Backend (#34)

> Final closeout (#30/#38): this document records its implementation stage.
> All Admin pages are now implemented. The field PWA retains `/login`, `/sync`,
> `/nodes` and `/cats`; Members, Site maintenance, Observations and standalone
> Visits use the server-rendered Admin backend. Cats remains in both surfaces.
> See [the final split](issue38-field-pwa.md).

Vertrag: [Issue #34](https://github.com/Grodahn/MiezMerker/issues/34).
Baut auf #33 (Admin-Shell/Login/Organisationskontext) und #32 (`AppUser.displayName`)
sowie #16 (Organisationen/Benutzer/Memberships/Login). Gehört zu Epic #30.

Keine zweite Frontend-Anwendung: serverseitiges Thymeleaf im bestehenden
Spring-Backend, wiederverwendetes Layout/Navigation aus #33.

## Route

- `GET /admin/members` – Mitgliederliste der aktiven Organisation (Admin-Layout,
  Navigation mit aktivem `Mitglieder`-Eintrag).
- `POST /admin/members/{membershipId}` – einzige Mutationsroute (PRG mit
  `?success=`/`?error=`). Form-Felder optional, mindestens eines erforderlich:
  `role`, `status`, `displayName`. Keine Mutation über GET.

## Angezeigte Felder

| Spalte | Quelle | Hinweise |
| --- | --- | --- |
| Name | `AppUser.displayName`, Fallback E-Mail | #32-Semantik: global, Unicode-getrimmt, `NULL` für Altbestände; UI zeigt E-Mail, persistiert nie einen aus der E-Mail abgeleiteten Namen |
| E-Mail | `AppUser.email` (normalisiert, eindeutig) | — |
| Rolle | `OrganizationMembership.role` (`ADMIN`/`MEMBER`) | — |
| Status | `OrganizationMembership.status` (`PENDING`/`ACTIVE`/`DISABLED`) | Badges; `PENDING` ist Status, keine Rolle |

Dazu: Seitenüberschrift, aktiver Organisationskontext (Name/Slug), Status-Labels,
leerer Zustand („Keine Mitglieder sichtbar“), Erfolgs-/Fehlerfeedback,
responsive Tabelle (Desktop-Tabelle, gestapelte Cards ≤640px).

## Verfügbare Pflegeaktionen (ADMIN-only)

Alle über `MemberService` (gemeinsame Service-Schicht mit der REST-API, kein
HTTP-Loop, keine duplizierte Geschäftslogik):

- **Rolle**: `ADMIN`/`MEMBER` (nur gültige Enums, sonst Redirect mit Fehler, keine Mutation).
- **Status**: `PENDING`/`ACTIVE`/`DISABLED` (gleiche Semantik wie `PATCH
  /api/v1/organizations/{id}/members/{mid}`; `DISABLED` wird durch reine
  Rollenänderung nicht reaktiviert).
- **Anzeigename**: globales `AppUser.displayName` (max. 255 nach Trim, Unicode
  erhalten, leer löscht zu `NULL`). **Achtung:** Änderung wirkt in allen
  Organisationen dieses Benutzers; keine organisationsspezifischen Aliase.
- Kein Last-ADMIN-Schutz (Stand #16): Demotieren/Deaktivieren des letzten ADMIN
  ist erlaubt und dokumentiert; die Organisation benötigt dann erneute
  ADMIN-Provisionierung.

Mitglieds-Erstellung (Bootstrap-/Testphase aus #16) bleibt vorerst API-only
(`POST /api/.../members`); das Admin-UI bietet keine Neuanlage an.

## Active-Organization-Scoping

- Aktive Organisation ausschließlich aus dem validierten serverseitigen
  Admin-Kontext (`AdminService`, Session-Key `ADMIN_ORG_ID`, geprüft via
  `TenantService.requireAdmin`). Browser-`organizationId` ist nie Nachweis.
- Liste: nur `findByOrganizationIdWithUser(activeOrg)`.
- Mutationen: `MemberService.requireMemberInOrganization` löst jede ID strikt
  innerhalb der aktiven Org auf; fremde/unbekannte IDs → `404` ohne Leak
  (keine E-Mail/Namen/Existenzpreisgabe, keine Enumeration).
- Org-Wechsel nur über `#33`-Mechanismus (`POST /admin/org`, validiert);
  Multi-Org-ADMIN sieht nach Wechsel ausschließlich die neue Org (keine
  stale-Daten).
- Ohne gültigen aktiven Kontext wählt die Liste die einzige ADMIN-Organisation
  oder leitet Multi-Org-ADMINs zu `/admin/org` weiter, auch nach PWA/API-Login.
  POST-Mutationen verlangen weiterhin einen bereits gewählten, gültigen Kontext.
- Ein unverändert übermittelter Status lässt Aktivierungs-/Deaktivierungszeitpunkte
  bestehen; nur tatsächliche Statuswechsel setzen diese neu.

## Autorisierung / Sicherheit

- Seite selbst ADMIN-only über #33 (`BoundaryConfiguration`: `/admin/**`
  erfordert ACTIVE ADMIN, sonst 403 via `/admin/denied`; unauthentifiziert →
  Redirect `/admin/login`, abgelaufen → `?expired`).
- Verteidigung in Tiefe: `AdminMembersController` prüft erneut
  (`adminOrgs` leer → 403, `requireActiveOrg`).
- `MEMBER`-only, `PENDING`/`DISABLED`-Memberships erhalten keinen Zugang.
- CSRF: alle Mutationen `POST` mit Thymeleaf-Token (`_csrf`); fehlend/ungültig
  → `403` ohne Zustandsänderung (Negativtests vorhanden). Kein `GET`-Mutieren,
  kein CSRF-Disable.
- Keine sensiblen Daten in Logs/URLs (generische Fehlermeldungen, keine
  Passwörter/Tokens; IDs nur als validierte Pfad-/Form-Parameter).

## #19-Integrationspunkt (keine Vorwegnahme)

Die Seite ist der künftige Einstieg für Invite-by-Mail (#19). Dafür existiert
ein HTML-Kommentar als Erweiterungspunkt („`Mitglied einladen` als POST-Form
hier ergänzen“). **#19 ist nicht implementiert:** kein Mailversand, keine
Invite-Tokens/Aktivierungslinks/Passwort-Mails/Self-Registration.

## PWA-Koexistenz (bis #38)

Die bestehende PWA-Seite `/admin/members` (`app/src/management/Management.tsx`,
Tabelle Name/E-Mail/Rolle/Status mit gleichem #32-Fallback) bleibt in diesem
Ticket unverändert; Cleanup folgt gesammelt in #38. Temporär existieren beide:

- PWA-Mitglieder (Feld-/Verwaltungs-PWA, API-basiert)
- Backend-`/admin/members` (serverseitig, sessionbasiert)

Keine PWA-Regression (gleiche REST-Semantik, `MemberService` wiederverwendet).

## Tests

`AdminMembersTest` (12 Integrationstests, `RANDOM_PORT`, H2):

- Rendering (ADMIN sieht Name/E-Mail/Rolle/Status, Fallback `NULL`→E-Mail, kein persistierter E-Mail-Name, Org-Kontext/Nav/CSRF).
- Tenant-Isolation A/B (A sieht nur A, fremde ID → 404 ohne Leak, DB unverändert).
- Multi-Org-ADMIN (A→B-Wechsel via `POST /admin/org`, keine stale-Daten).
- Autorisierung (unauthentifiziert → Login-Redirect, MEMBER/PENDING/DISABLED → 403).
- Mutationen (gültig persistiert, fremd → 404, ungültig → Redirect+Fehler ohne Mutation, CSRF fehlt/ungültig → 403).
- `displayName` (Unicode/Trim, überlang → Fehler, blank löscht, global konsistent).
- `PENDING`-Status (keine Rolle, Aktivierung möglich).
- Direkter Einstieg nach API-Login: Single-Org-Auswahl und Multi-Org-Weiterleitung.
- Rollen-/Statusformular erhält Zeitpunkte bei unverändertem Status.

Dazu unverändert: `AdminShellTest` (#33), `DisplayNameTest` (#32),
`TenancyAuthTest` (#16), Backend-Suite, OpenAPI-Drift nur bei API-Änderung
(hier keine: `MemberController`-Refaktor nutzt dieselben DTOs).
