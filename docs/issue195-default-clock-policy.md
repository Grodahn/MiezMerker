# Checkout → claim a bowl: field-alpha default (#195)

Current local development/field-test contract: **FIELD_ALPHA_PHONE_CLOCK** in
backend and PWA; the documented ESP32-C3 `sdkconfig.dev` selects the same policy.
No UTC attestation or signed time-bootstrap exchange is needed for alpha claiming
or offline sync. Issuer provisioning is still required once per installation.

## Standard Windows path

1. Keep PostgreSQL, JDK and the existing HTTPS/Web Bluetooth setup from
   [dev-demo.md](dev-demo.md). Run `& ./scripts/dev-issuer.ps1` at repository root.
   Reuse the existing issuer; never rotate/delete it to repair a build. This
   deliberately leaves `TIME_BOOTSTRAP_CLOCK_HEALTHY=false`. Start the backend
   from that shell with `./backend/mvnw.cmd -f backend/pom.xml spring-boot:run`,
   or load the generated private properties URI into the existing IntelliJ run
   configuration as described in [local-onboarding.md](local-onboarding.md).
   Restart IntelliJ's backend; it does not inherit a different shell's exports.
2. In an activated ESP-IDF terminal, use the existing independent issuer pin.
   On a fresh configuration append its **public** `sdkconfig.issuer.defaults`
   path to `SDKCONFIG_DEFAULTS`; on an existing configuration verify the pin
   with `scripts/dev-issuer.ps1 -CheckFirmwarePin <file>`. Then, from
   `firmware-esp32/idf` (COM6 is an example; identify your board/port first):

   ```powershell
   idf.py -B build-dev -D SDKCONFIG=sdkconfig.local-dev `
     -D 'SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.dev' `
     -D MM_DEVELOPMENT_BUILD=ON -p COM6 build flash size
   ```

   A pre-existing signed `sdkconfig.local-dev` fails the build **before flash**.
   Change only its `# CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK is not set` line to
   `CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK=y` and rerun. Do not delete the config,
   issuer pin, NVS, identity or observations. The reverse mismatch also fails.
   A cached `MM_SECURITY_CLOCK_POLICY` cannot override the chosen defaults profile;
   remove only that stale selector with `-U MM_SECURITY_CLOCK_POLICY` when reported.
   ESP-IDF defaults seed missing values; they never migrate existing values.
   The Windows `tools/idf.ps1` wrapper accepts the same arguments.
3. From `app`, run `npm ci` once, then `npm run dev:https` for Android Chrome
   (`npm run dev` for localhost). Ordinary `npm run build` + `npm run preview`
   also uses alpha. No policy environment variable is needed. Sign in as an
   ACTIVE organization ADMIN, choose **Napf hinzufügen**, confirm the selected
   UNCLAIMED Node and claim it online. No `/nodes/time-bootstrap` request occurs.
   The backend still verifies the Node signature/ownership and signs the receipt;
   the ESP verifies it against its independent issuer pin before storing ownership.
4. Cache a valid credential, AppDevice key and Node key, restart the bowl and
   put the phone offline. Sync still authenticates Node and client, stores records
   before ACK and keeps pending server uploads distinct from local success.
   Alpha session time is RAM-only and must be sampled again after disconnect/reboot.

## Diagnose actual policy and stale clients

Serial logs include firmware/git version, `security_clock_policy=...` and issuer
fingerprint. `security_time=SECURITY_TIME_UNKNOWN` is expected in alpha: phone
time never establishes trusted UTC. PWA **Zeit-Policy / Diagnose** shows the
compiled policy and risk; Vite logs it at start/build. Backend startup logs the
provisioning policy and claim freshness assumptions. It cannot attest a remote
Node's mode: the existing claim wire message carries no trustworthy policy claim.

PWA/FW disagreement names **both actual modes** and stops before protected BLE
operations. Invalid PWA policy values fail build/start. Vite settings are compiled
into the bundle: changing the shell/env file requires a Vite restart or rebuild.
`preview` serves the existing bundle and cannot change its policy. On installed
PWAs accept the service-worker update and reload (close old tabs); inspect the
diagnostic again. Do not erase IndexedDB/pending observations to repair an app cache.
Preview startup therefore ignores current policy environment variables (including
invalid values) and points to the bundle's UI diagnostic instead of claiming to
compile a new policy. Build/dev startup still validates policy and release gates.

## Deliberate signed UTC / release path

Use separate build directories **and** SDKCONFIG files:

```powershell
# From firmware-esp32/idf; append the same public issuer defaults for a NEW config.
idf.py -B build-signed-dev -D SDKCONFIG=sdkconfig.local-signed-dev `
  -D 'SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.dev;sdkconfig.signed-time' `
  -D MM_SECURITY_CLOCK_POLICY=SIGNED_BACKEND_TIME -D MM_DEVELOPMENT_BUILD=ON build size
# Hardened firmware: sdkconfig.defaults only, separate build-production/config,
# MM_DEVELOPMENT_BUILD=OFF. Development inputs and stale alpha configs fail closed.

# From app:
npm run build:signed-time
# Or the hardened release gate (rejects inherited alpha environment settings):
npm run build:release
npm run preview
```

For signed-time HTTPS dev: `npm run dev:https -- --mode local-https` with
`VITE_MM_SECURITY_CLOCK_POLICY=SIGNED_BACKEND_TIME` set before starting Vite.
For the backend set `MM_SECURITY_CLOCK_POLICY=SIGNED_BACKEND_TIME`, provision the
same issuer and explicitly attest healthy server UTC following
[local-onboarding.md](local-onboarding.md). `TIME_BOOTSTRAP_CLOCK_HEALTHY=false`
continues to deny the signed UTC service. It never blocks legitimate alpha claims.
For go-live use Spring's `release` profile (defaults to signed and rejects alpha)
or `MM_RELEASE=true` with the explicit signed policy. Provisioning policy alone
does not add remote attestation or revoke previously issued offline credentials.

The firmware-only `sdkconfig.field-alpha` variant requires explicit
`-DMM_ALLOW_FIELD_ALPHA=ON` outside development; this is recorded in configure
output and CI artifact names. It is field-test risk acceptance, never a hardened
release. CI builds production/signed, dev/alpha, field-alpha and signed-dev,
checks effective generated config and mock-symbol exclusion, and tests rejecting
old caches and release misuse. PWA CI builds the signed release and default alpha.
Go-live checklists #113/#152 must record all three policies and require deliberate
signed builds; ordinary `npm run build` is a **field-test artifact**.

## Accepted security limits and evidence

A former authorized person with an old/valid credential **and matching AppDevice
private key** can backdate a new phone session to bypass expiry, including
ACK/COMPACT and possible deletion of pending records. This is not cryptographically
trusted smartphone UTC. JWT/ES256, issuer pin, Node signature, PoP, credential,
organization/ADMIN checks, iat/exp checks and persist-before-ACK remain intact.
The backend compares initial claim timestamps against its own ±5-minute window;
in alpha this limits submitted timestamps, not independently proven proof age or
live presence. Stockpiled future-dated advertisements remain the accepted #171 risk.
Same-owner/key recovery retains its existing expired-proof exception.

Automated evidence includes profile/cache/release guards, signed-mode compatibility,
alpha claim without UTC health, PWA mismatch, reboot/offline retry and ACK ordering.
Actual **ESP32-C3 + Android Chrome claim/reboot/offline sync: UNVERIFIED** until
performed physically. Follow the full hardware/security procedure in
[issue171-field-alpha-phone-clock.md](issue171-field-alpha-phone-clock.md);
host tests and CI do not establish physical success.

## Integrated review and local completion

Stage 0 mapped the changed build-time policy selectors and their direct consumers:
registration preflight/provisioning, offline SyncEngine and shell diagnostics;
IDF defaults/effective SDKCONFIG and existing release guards; backend provisioning
diagnostics versus the independent claim verifier and UTC issuer. No schema, BLE
wire format, persisted identity, tenant, issuer trust or ACK boundary is changed.
No open parallel PR touched these boundaries at implementation start.

The integrated security review checked mismatch-before-auth ordering, retained
revocation/cancellation fences, alpha RAM-only lifetime, signed receipt verification,
server claim-window assumptions and fail-closed release configuration. Repairs
preserved the existing development-input release error before the new alpha gate,
prevented cached explicit CMake selectors from overriding a newly requested profile,
added a compiled PWA build identifier for cache diagnosis, and updated browser
fixtures to handle alpha time separately from ownership receipts. Both PWA modes
retain the complete registration/focus/confirmation assertions.

Local completion: 389 backend node/auth tests; 639 frontend tests; 41 firmware
auth/BLE/lifecycle tests and four real-verifier offline-auth tests; nine default
alpha browser cases and four signed browser cases; TypeScript, both PWA builds
and generated API drift check. A first parallel frontend run timed out under load;
the failing files passed first, then the complete suite passed with two workers,
without changing assertions/timeouts. Full final-head PostgreSQL, browser and
four-profile ESP32 image/size CI remains mandatory before PR closeout.

Follow-up review repaired a misleading preview startup diagnostic: a signed bundle
could be served while the preview process logged the default alpha policy, or an
unrelated invalid shell value could block it. A real Vite configuration regression
now checks signed build defines, preview without replacement policy/build defines,
and continued rejection of invalid policy during build/dev startup.
