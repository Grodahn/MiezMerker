# Guided bowl registration (#121)

The existing “Weiteren Napf hinzufügen” action opens the service-filtered native
Bluetooth chooser directly from its click handler. Browser permission is retained
in the existing device list; subsequent batch synchronization uses `getDevices()`.
Permission alone never establishes proximity, identity or ownership.

The existing public GATT probe connects first. An ACTIVE organization ADMIN sees
the Ja/Nein dialog only after a read-only, cryptographically verified physical
claim advertisement binds the device key to the public UUID. Nein/Escape causes
no claim or deployment. Ja calls the existing #18 provisioning implementation:
backend signature/freshness verification, signed receipt, physical claim mode,
independent firmware issuer pin and trusted UTC checks remain mandatory. A stale
advertisement cannot authorize a new backend claim. SYSADMIN alone grants no
organization rights; MEMBER receives an administrator explanation for UNCLAIMED.

Already owned bowls use the existing authenticated #76 synchronization. No second
claim is sent. After verified same-organization synchronization, ADMIN can resume
initial setup when there is no deployment history. Foreign selection gets a generic
message without organization metadata or access to protected observations.

The existing #53 wizard and protected APIs save an optional name and first
deployment to an existing organization FeedingSite. Initial setup never moves a
bowl with deployment history. Existing open deployments are reused, concurrent
overlap conflicts are resolved by reading persisted state, and no new FeedingSite
is created. Name success survives deployment failure. Backend ownership and local
receipts survive delivery interruption; confirmed claims resume setup without
claiming again. The local hint is optional; backend state is authoritative.

Setup requests latch cancellation across organization changes (including away and
back), logout and role loss. Pending reads/CSRF cannot subsequently cause a write
in a different context. Late component results are discarded. Native dialog
semantics contain keyboard focus, focus Nein initially, focus the setup input and
restore focus to the add action. Main Sync and collapsed diagnostics stay intact.

## Verification

- Collector/platform regression: **293 tests passed**.
- Backend node/auth regression: **255 tests passed**.
- TypeScript and production PWA build: **passed**.
- Existing Chromium status/Sync UX: **14 tests passed**.
- New Chromium guided registration: **2 tests passed**, at 320/430px, with real
  Web Crypto advertisement signatures but mocked GATT and backend responses.
  These check activation, explicit chooser, no claim before consent, Escape,
  focus restoration, name/site setup, no horizontal overflow and success.
- Focused repair checks passed; reports and screenshots are retained in ignored
  `test-results/compact/` and `app/test-results/`.
- Independent reviewer: one MAJOR (away-and-back setup race) and one MINOR
  (persisted completion hint) confirmed, repaired and rechecked. Browser tests
  additionally detected initial dialog focus; explicit focus fixed it. No
  confirmed BLOCKER/MAJOR remains.

## Physical acceptance and blockers

**Android/ESP32: UNVERIFIED for this issue.** No physical device acceptance was
performed. Current ESP32-C3 composition reports unavailable trusted UTC; an issuer
public-key pin must also be configured independently. See
[firmware prerequisites](../firmware-esp32/README.md#clock-issuer-trust-and-claiming)
and [existing hardware evidence](../firmware-esp32/hardware/issue108.md).
Missing prerequisites remain fail-closed. The current GATT error does not separately
identify inactive physical claim mode versus absent UTC; UI reports both checks
without pretending to know which failed. Receipt rejection also names issuer pin
and UTC prerequisites. No clock, signature, expiry or trust bypass was added.

Efficiency: Windows sandbox temporary-file isolation broke Vitest; subsequent
tests used normal host execution. A few guessed paths were corrected with scoped
file discovery. Focused failures were rerun first; broader CI matrices are left to
GitHub rather than repeatedly duplicated locally.
