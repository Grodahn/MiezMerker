# Auth-Gate der PWA (#31)

Vertrag: [Issue #31](https://github.com/Grodahn/MiezMerker/issues/31), vor der
Implementierung von GitHub gelesen. Ausgangspunkt ist `main` mit den gemergten
PRs für #8 (Collector), #11 (Management) und #16 (Session/Memberships).

## Zustandsmaschine (ein zentrales Gate, kein zweites Auth-Modell)

`app/src/platform/gate.ts` (`useAppGate`, `resolveGateStatus`) entscheidet
zentral zwischen drei Renderzuständen. `app/src/platform/auth.ts` bleibt das
einzige Auth-State-Modell (User, Memberships, aktive Organisation); neu sind
nur `sessionChecked` (initiale Online-Prüfung versucht) und `sessionVerified`
(Backend hat die Session online bestätigt).

- **login-only:** keine verifizierte Online-Session und keine gültige
  Offline-Sync-Berechtigung. Es wird ausschließlich `LoginView` ohne
  Navigation und ohne geschützte Inhalte gerendert – auch bei direkter URL auf
  `/sync`, `/nodes`, `/cats`, `/sites`, `/observations`, `/visits` oder
  `/admin/members`. Die URL bleibt auf dem gewünschten Pfad (Rückkehr nach
  Login), der Inhalt ist trotzdem nur Login.
- **authenticated:** `user + sessionVerified + navigator.onLine`. Normale PWA
  gemäß AppUser, ACTIVE Memberships, aktiver Organisation und
  Rollenbeschränkungen (ADMIN/MEMBER wie in #11/#16). Navigation, `AuthPanel`
  (Logout/Organisationswahl) und alle Routen gemäß bestehender Logik.
- **offline-sync-only:** kein verifizierter Online-Zugang, aber Snapshot mit
  `user + activeOrganizationId` aus zuvor legitim hergestellter Anmeldung plus
  noch gültiges Offline-Credential (`OfflineIdentity.credential`) für genau
  diese Organisation. Dann ist **nur** `/`/`/sync` mit `CollectorShell`
  nutzbar; Verwaltungsrouten bleiben gesperrt (Login/gsprechene Ansicht),
  es werden keine alten Verwaltungsdaten gezeigt.

## Warum Offline-`/sync` kein anonymer Zugriff ist

Offline-`/sync` erfordert kumulativ:

1. zuvor online erzeugte lokale AppDevice-Identität (nicht exportierbarer
   Web-Crypto-Key, `deviceId` vom Backend registriert),
2. bekannten ACTIVE-Membership-/Organisationskontext aus der letzten
   verifizierten Anmeldung (Snapshot in `localStorage`, nur ACTIVE gefiltert),
3. zeitlich noch gültiges, an `user/org/device/public-key` gebundenes
   Offline-Credential aus Dexie (`expiresAt`, `deviceId`-Bindung).

Ohne alle drei zeigt auch offline `/sync` nur Login/gesperrt. Ein 401 vom
Backend (`fetchSession`, Management-`result`, Upload) ruft zentral
`markSessionExpired()` und zwingt das Gate zurück auf login/offline-sync-only;
`contextSignal`/Generation bricht alte Verwaltungs-Requests ab, das
Management unmountet und verwirft In-Memory-View-State (kein Stale-A nach
Logout/Sessionablauf, #11-Schutz bleibt).

## Start, Login, Logout, Ablauf

- **Initial load:** Gate zeigt neutral `Anmeldung wird geprüft …` bis
  `sessionChecked` (online `fetchSession`, offline `markOfflineChecked`) plus
  ggf. Credential-Lookup abgeschlossen ist. Kein Flash von Navigation oder
  privaten Inhalten.
- **Login:** `LoginView` nutzt `login()` wie bisher (Session-/CSRF-Cookies,
  Organisations-Restore/-Wahl in `auth.ts`); nach Erfolg rendert das Gate ohne
  Reload die passende Shell.
- **Logout:** `logout()` löscht Session/Snapshot, `forgetCredentials` der
  Erneuerung entfernt Credentials (AppDevice-Key und Outbox bleiben gemäß
  bestehender Offline-Security-Semantik erhalten); Gate zeigt sofort Login,
  geschützte Navigation/Inhalte verschwinden, Management-State wird durch
  Unmount verworfen.
- **Backend-Gate bleibt maßgeblich:** Frontend-Gating ist UX/Privacy
  Defense-in-Depth; Tenant-/Rollenprüfungen serverseitig bleiben autoritativ.

## Prüfungen

- Unit: `gate.test.ts` (reine Statuslogik), `App.test.tsx` (frisch/login-only
  auf allen Routen, Login ohne Reload, Ablauf/Logout ohne Stale-Daten,
  Offline mit/ohne Credential, kein Flash, Org-Wechsel), `Management.test`
  (401 → Login-Prompt ohne Leak), bestehende #8/#11/#16/#17-Tests.
- Browser: `foundation.spec` (frisch nur Login, offline ohne Credential
  gesperrt, Outbox-Durability), `management.spec` (authentifizierter Ablauf,
  Org-Isolation, History-Lifecycle).
- `tsc -b`, `vite build`, API-Drift falls generierte Dateien angefasst wurden
  (hier nicht nötig – kein OpenAPI-Change).
