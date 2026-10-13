# Local signed UTC onboarding (#157)

Use PowerShell **7.2+** (`pwsh`), your existing IntelliJ JDK/Spring Boot,
PostgreSQL and ESP-IDF 6.1 installation. No OpenSSL or Python crypto package is
required. This is local development provisioning, independent of demo seeding.
Production defaults stay closed; do not deploy this static UTC attestation as
live clock monitoring. The phone/browser clock never establishes trusted UTC.

## 1. Persist the issuer outside Git

From PowerShell 7:

```powershell
Set-Location C:\dev\MiezMerker
& ./scripts/dev-issuer.ps1
$issuerDir = Join-Path $env:LOCALAPPDATA 'MiezMerker/issuer'
```

The script creates an ES256 P-256 pair once, validates/reuses it thereafter,
and limits the directory/files to your Windows user (Unix: directory 0700,
files 0600). Keep this directory private and back it up securely. Never paste
`issuer.json` or `backend-issuer.properties` into chat, logs, Git or shared
IntelliJ run configurations. Only `sdkconfig.issuer.defaults` and the printed
SHA256 of the public `04||X||Y` point are public. No private key goes to ESP-IDF.
Do not run this script with PowerShell tracing/transcription of secret variables.

If this installation already uses stable keys, supply the existing
`ISSUER_PRIVATE_PKCS8_B64` / `ISSUER_PUBLIC_SPKI_B64` through your existing private
environment source and run `& ./scripts/dev-issuer.ps1 -ImportFromEnvironment`.
It validates the matching P-256 pair and refuses to replace a different stored
pair. Do not generate a new pair for an existing fleet. Missing/corrupt stores
must be restored from backup, never silently rotated. Another path is supported
with `-KeyDirectory`, but must remain outside the checkout.

## 2. Check backend UTC, then attest explicitly

Run on the machine actually hosting Spring Boot:

```powershell
w32tm /query /status /verbose
w32tm /query /source
w32tm /stripchart /computer:time.windows.com /samples:5 /dataonly
Get-Date -AsUTC
# Only after checking recent synchronization, a trusted source and acceptable offset:
& ./scripts/dev-issuer.ps1 -AttestHealthyUtc
```

Check your configured trusted time source; `time.windows.com` is an example
comparison source, not a new application dependency. Unsynchronized/local CMOS
sources, stale last-sync dates, large offsets or failed queries are not proof of
healthy UTC. Repair Windows Time first (resync may require an elevated shell).
Budget backend sampling error within ADR 0017's 1-second server uncertainty.
The switch is **your explicit operator attestation**, not automatic NTP checking.
It sets `TIME_BOOTSTRAP_CLOCK_HEALTHY=true` only in the current shell; omitting
the switch explicitly sets it false. Recheck before each backend run and after
sleep, clock changes or network problems. Stop issuance/restart with false if
health is lost. Production needs trusted ongoing monitoring calling
`BootstrapTimeSource.reportHealthy(false)` on loss, or issuance stopped; a static
property cannot detect clock degradation.

## 3. Configure IntelliJ and restart Spring Boot

In your **local, unshared** Spring Boot Run/Debug configuration, add these
environment variables (replace `Andre` if needed):

```text
SPRING_CONFIG_ADDITIONAL_LOCATION=file:///C:/Users/Andre/AppData/Local/MiezMerker/issuer/backend-issuer.properties
TIME_BOOTSTRAP_CLOCK_HEALTHY=true
```

Copy the printed configuration path as a file URI for custom paths. The location
is required, not `optional:`: a missing file must prevent startup. The second
variable records the explicit check in step 2; use false until that check passes.
If additional locations already configure your database, append the issuer URI
to that variable with a comma; the script preserves these locations automatically.
Keep your existing database/auth/profile configuration. Remove conflicting
issuer environment variables, JVM `-D` settings or command-line property
overrides. Restart the backend; an already running IntelliJ process does not
inherit changes to the current shell environment. Startup must say
`Using configured credential issuer key`, never `Using ephemeral credential issuer key`.
Malformed, non-P256 or mismatched pairs fail startup.

Alternatively invoke the script with the attestation and run the backend
from that same shell: `./backend/mvnw.cmd -f backend/pom.xml spring-boot:run`.
Never print the issuer properties file for validation.

## 4. Pin independently, build and reflash

The generated defaults contain exactly 130 hex characters (`04||X||Y`). ESP-IDF
defaults do **not** override an existing sdkconfig, so use menuconfig to set the
pin explicitly for an existing build. In an EIM-activated ESP-IDF 6.1 PowerShell
terminal, with PowerShell 7:

```powershell
Set-Location C:\dev\MiezMerker
& ./scripts/dev-issuer.ps1
$issuerDir = Join-Path $env:LOCALAPPDATA 'MiezMerker/issuer'
$pinDefaults = (Join-Path $issuerDir 'sdkconfig.issuer.defaults').Replace('\','/')
Push-Location firmware-esp32/idf
idf.py -B build-local-onboarding -D SDKCONFIG=sdkconfig.local-onboarding `
  -D "SDKCONFIG_DEFAULTS=sdkconfig.defaults;$pinDefaults" -D MM_DEVELOPMENT_BUILD=OFF menuconfig
# MiezMerker board -> issuer public key: use the value from the PUBLIC defaults file.
# Save/exit. A fresh config inherits the generated pin automatically.
Pop-Location
& ./scripts/dev-issuer.ps1 -CheckFirmwarePin firmware-esp32/idf/sdkconfig.local-onboarding
Push-Location firmware-esp32/idf
idf.py -B build-local-onboarding -D SDKCONFIG=sdkconfig.local-onboarding `
  -D "SDKCONFIG_DEFAULTS=sdkconfig.defaults;$pinDefaults" -D MM_DEVELOPMENT_BUILD=OFF build size
Pop-Location
& ./scripts/dev-issuer.ps1 -CheckFirmwarePin firmware-esp32/idf/sdkconfig.local-onboarding
```

For the existing development-button profile, add `sdkconfig.dev` to the defaults
list and use `MM_DEVELOPMENT_BUILD=ON`; see the board README. The helper
`firmware-esp32/tools/idf.ps1 -IdfArguments @(...)` supports the same arguments
without an activated terminal. The default `sdkconfig.defaults` already targets
ESP32-C3; do not reset target/config afterward and lose the pin.

Confirm your actual USB COM port, chip and hardware MAC as described in
[the board instructions](../firmware-esp32/README.md#windows-build-and-port-selection).
Then use the geometry/MAC-checking wrapper (replace both examples):

```powershell
$idfPython = 'C:\Espressif\tools\python\v6.1\venv\Scripts\python.exe'
& $idfPython firmware-esp32/tools/flash_board.py --port COM6 `
  --expected-mac 44:b1:76:18:ca:78 --build-dir firmware-esp32/idf/build-local-onboarding
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset `
  --seconds 15 --output test-results/local-onboarding-boot.txt
```

Rebuild/reflash is required when the flashed pin is missing/different or the
registration bootstrap firmware is old. Do **not** erase NVS to repair a pin;
normal flashing preserves node identity/ownership/data. Boot should show
`security_time=UNKNOWN issuer_pin=configured` and `issuer_pin SHA256(point)=...`
matching the setup output **exactly**. Checking sdkconfig proves build intent;
only this serial comparison proves the running firmware's pin. UNKNOWN at boot
is correct. A missing pin denies time/claim/sync. A mismatched valid pin rejects
the proof; neither PWA nor `/issuer` may replace it. Intentional rotation needs
coordinated backend and firmware provisioning.

## 5. Validate Android onboarding and sync

Use the existing HTTPS/Web Bluetooth PWA setup and an ACTIVE organization ADMIN.
For a fresh UNCLAIMED node, “Napf hinzufügen” obtains the device-signed time
challenge, requests a proof using authenticated session/CSRF, forwards the exact
JWS to the ESP's independent verifier, and only then obtains the signed claim
advertisement. The backend verifies that advertisement/ownership and returns a
claim receipt. Firmware verifies/stores it; no physical claim gesture is needed.
Continue reading/synchronizing: normal offline credentials, device proof,
ownership, protected BLE batch/ACK and server upload gates still apply.

The ADMIN preflight retains the UNCLAIMED public-discovery connection through
the signed time/identity check, then closes it before the confirmation dialog.
This avoids an immediate Android reconnect/service-discovery transition. A
failure during connection setup now names GATT connection, service discovery,
or characteristic discovery; this diagnostic alone does not prove a hardware fix.

Expected: registration completes and normal sync succeeds. Time bootstrap alone
grants no ownership or observation access. Test an interrupted claim/reboot with
the same ADMIN and exact device key; same-owner recovery remains supported.
Do not mark an upload successful based only on BLE ACK.

Troubleshooting:

| Signal | Action |
| --- | --- |
| ADMIN HTTP 503 `issuer-key-missing` | Load persistent issuer properties in the running backend and restart; compare firmware fingerprint. |
| ADMIN HTTP 503 `utc-unconfirmed` | Check backend machine UTC/NTP, attest explicitly, restart; keep closed if unhealthy. |
| Generic 503 for non-ADMIN | Ask the deployment operator; operational diagnostics are restricted. |
| `issuer_pin=missing` | Set independent public pin, rebuild/reflash. |
| ESP fingerprint differs from setup | Refuse onboarding; restore intended issuer or reflash the coordinated pin. |
| Proof rejected | Compare pins first; reconnect for a fresh challenge. Tampered, stale, replayed and wrong-boot proofs remain rejected. |
| HTTP 401/403/409/429 | Check login/CSRF, active ADMIN, ownership/key conflict or retry budget; no security bypass. |

Host tests exercise signed proof validation, pin mismatch, replay rejection,
ADMIN claiming and sync regression. They do not establish physical Android/ESP
E2E success; record that sequence on hardware separately without logging proofs,
private keys, raw RFID identifiers or credential bodies.
