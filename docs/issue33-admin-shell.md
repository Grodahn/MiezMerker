# Admin-Backend Shell (#33)

Vertrag: [Issue #33](https://github.com/Grodahn/MiezMerker/issues/33).
Abhängig von #32 (`AppUser.displayName`), verwandt mit #16 (Tenancy/Auth) und #31 (PWA-Gate).
Gehört zu Epic #30 (Trennung Feld-PWA `/app` und Backoffice `/admin`).

## Technik (keine zweite SPA, kein zweites Auth-System)

- Spring MVC + Thymeleaf direkt im bestehenden Spring-Boot-Backend.
- Spring Security Form-Login auf `POST /admin/login` mit denselben
  `AppUser`-Entitäten, BCrypt-Hashes (`PasswordEncoder`), `AppUserDetailsService`
  und serverseitiger Session/CSRF-Infrastruktur wie die PWA/API (`/api/v1/auth/*`).
- Session-Fixation-Schutz über die bestehende
  `ChangeSessionIdAuthenticationStrategy` + `CsrfAuthenticationStrategy`.
- Keine React-SPA unter `/admin`, keine eigene Benutzer-/Passwortablage.

## Routen

- `GET /admin/login` – öffentliche Login-Seite ohne PWA-Shell (E-Mail + Passwort + CSRF).
- `POST /admin/login` – Spring-Security-Form-Login (`email` als Username-Parameter),
  Erfolg via `AdminAuthSuccessHandler` (aktualisiert `last_login_at` wie die API,
  initialisiert den Session-Org-Kontext), Fehler nach `/admin/login?error`.
- `POST /admin/logout` – Spring-Security-Logout, invalidiert die Session,
  löscht `JSESSIONID`, Erfolg nach `/admin/login?logout` (CSRF-pflichtig).
- `GET /admin/` – Dashboard mit allen Backoffice-Bereichen (Mitglieder,
  Futterstellen, Katzen, Rohbeobachtungen, Besuche).
- `GET /admin/org` + `POST /admin/org` – Organisationsauswahl/-wechsel.
- `GET /admin/denied` – wiederverwendbare 403-Seite (GET + POST, immer 403).
- Stubs für Folge-Tickets (noch keine Fachlogik aus #34–#37):
  `GET /admin/members`, `/admin/sites`, `/admin/cats`,
  `/admin/observations`, `/admin/visits` rendern dasselbe Layout mit Platzhalter.

## Autorisierung / Organisationskontext

- `/admin/login` und `/admin/denied` sind `permitAll`; alles andere unter
  `/admin/**` erfordert mindestens eine `ACTIVE ADMIN`-Membership in einer
  `ACTIVE` Organisation bei `ACTIVE` Benutzerstatus (`BoundaryConfiguration`).
  `MEMBER`-only, `PENDING` und `DISABLED` erhalten 403 (kein Dashboard).
- Aktiver Organisationskontext liegt ausschließlich serverseitig in der
  `HttpSession` (`ADMIN_ORG_ID`). `AdminService` validiert ihn bei jedem Zugriff
  über `TenantService.requireAdmin`; eine clientseitige `organization_id` ist nie
  Berechtigungsnachweis.
- Eine `ACTIVE ADMIN`-Org: automatisch aktiv. Mehrere: `GET /admin/` leitet nach
  `/admin/org` zur expliziten Wahl. `POST /admin/org` validiert die Ziel-Org
  (fremde IDs → 403/404 ohne Leak, Session-Org bleibt unverändert).
- Anzeigename aus #32: Layout zeigt `displayName` (Fallback E-Mail) und aktiven
  Organisationsnamen plus Logout.

## Navigation / Layout

Wiederverwendbare Thymeleaf-Fragmente in `templates/admin/layout.html`
(`head`, `topbar`, `nav`, `footer`) mit einfacher responsiver Inline-CSS:

- Topbar: aktiver Organisationsname, Benutzerlabel (Name/E-Mail), Logout-Form.
- Hauptnavigation: Übersicht, Mitglieder, Futterstellen, Katzen,
  Rohbeobachtungen, Besuche; bei mehreren Admin-Orgs zusätzlich Org-Wechsler
  (`POST /admin/org`) und Link zur Auswahlseite.
- Login-Seite ohne Navigation; 403-Seite ohne Tenant-Leak; `?error`,
  `?logout` und `?expired` mit verständlichen Meldungen. Unauthentifizierte
  `/admin/`-Zugriffe leiten nach `/admin/login` (abgelaufene Session nach
  `/admin/login?expired`).

## Sicherheit

- CSRF für alle Mutationen (`/admin/login`, `/admin/logout`, `/admin/org`);
  fehlende Token → 403 über `/admin/denied` (POST-fähig, kein 405-Leak).
- Keine sensiblen Daten in Logs/URLs (generische Login-Fehlermeldung, keine
  Passwort-/Token-Logs, IDs nur als validierte Form-Parameter).
- Direkte fremde Org-IDs umgehen Tenant-Gates nie (Negativtests A gegen B).

## Tests

`AdminShellTest` (12 Integrationstests, `RANDOM_PORT`, H2):

- Login-Seite öffentlich ohne PWA-Shell; unauthentifiziert → Redirect zum Login.
- ADMIN-Login erfolgreich, Dashboard listet alle Bereiche, Org/User/Logout sichtbar.
- Gleiche Benutzer wie PWA (API-Login mit denselben Credentials).
- MEMBER-only, PENDING, DISABLED-Membership → 403; DISABLED-Account → `?error`.
- Multi-Org-ADMIN: Auswahlseite, validierter Wechsel A↔B.
- Fremde Org-ID beim Wechsel → 403/404, Kontext bleibt A.
- Logout invalidiert die Session; CSRF-freie POSTs → 403 ohne Zustandsänderung.
- Session-Ablauf (1s-Timeout) → Redirect zu `/admin/login` (`?expired`).
- Stubs nutzen dasselbe Layout und erfordern ADMIN.

Alle 134 Backend-Tests (inkl. `TenancyAuthTest`, `DisplayNameTest`) bleiben grün;
bestehende REST-/PWA-APIs, Cookies (`HttpOnly`/`Secure`/`SameSite=Lax`) und
CSRF-Semantik sind unverändert.
