# Local signed UTC onboarding and issuer setup (#157, #159)

Use Windows PowerShell **5.1** or PowerShell **7.2+** (`pwsh`), your existing IntelliJ JDK/Spring Boot,
PostgreSQL and ESP-IDF 6.1 installation. No OpenSSL or Python crypto package is
required. PowerShell 5.1 uses the bundled `scripts/DevIssuer.java` helper with
your backend JDK (21 recommended, 17 minimum); keys travel through captured
process pipes, never command-line arguments or terminal output. Set `JAVA_HOME`
or pass `-JavaExecutable 'C:\path\to\jdk\bin\java.exe'` if IntelliJ's JDK is not
on PATH. Both shells preserve exactly the same existing issuer store and pin.
This is local development provisioning, independent of demo seeding.
Production defaults stay closed; do not deploy this static UTC attestation as
live clock monitoring. The phone/browser clock never establishes trusted UTC.

## 1. Persist the issuer outside Git

From Windows PowerShell 5.1 or PowerShell 7:

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
[DateTime]::UtcNow
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
terminal, with Windows PowerShell 5.1 or PowerShell 7:

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

The PWA has no issuer secret and configures no firmware pin; it transports signed
artifacts. Never adopt a trust anchor from an untrusted HTTP `/issuer` response.

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

## 6. Conflicting configuration and safe recovery

Setup validates existing `backend-issuer.properties`, `sdkconfig.issuer.defaults`
and inherited issuer variables before changing any export. Malformed files,
duplicate pin assignments and mismatches abort. Restore the intended configuration
from an encrypted, operator-only backup. If only a generated export was damaged,
verify and back up `issuer.json`, remove only that damaged export, then rerun.
Never delete the pair to repair an export. A remaining public defaults file also
blocks fresh generation when the pair is missing: it may belong to an existing fleet.
An explicit `-CheckFirmwarePin` always requires an existing configuration file,
even when it names `sdkconfig.issuer.defaults`; it never creates the file being
checked. Run setup without that option to recover missing exports from a valid pair.

Keep an encrypted backup of the entire issuer directory. Test restoration to a
protected directory outside Git using `-KeyDirectory` and compare the recorded
public fingerprint. Private keys cannot be recovered from firmware or `/issuer`.
The helper cannot inspect arbitrary IntelliJ JVM/command-line overrides or prove
which firmware is running: compare backend startup and ESP serial fingerprints.

## 7. Manual Windows fallback

Use OpenSSL on PATH with Windows PowerShell 5.1 or 7. This is a fresh-installation
fallback, never a repair for a fleet with existing keys. Start in a private shell
without tracing/transcription. Choose a NEW directory outside every Git checkout.
The following commands write secret files with an ACL limited to your user:

```powershell
$ErrorActionPreference = 'Stop'
$manualDir = Join-Path $env:LOCALAPPDATA 'MiezMerker/issuer-manual'
if (Test-Path -LiteralPath $manualDir) { throw 'Existing directory: restore/reuse instead' }
[void][IO.Directory]::CreateDirectory($manualDir)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetOwner($sid)
$acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
  $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
if ($PSVersionTable.PSEdition -eq 'Desktop') { [IO.DirectoryInfo]::new($manualDir).SetAccessControl($acl) }
else { [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($manualDir), $acl) }
& openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$manualDir/issuer.pem"
if ($LASTEXITCODE -ne 0) { throw 'Generation failed' }
& openssl pkcs8 -topk8 -nocrypt -in "$manualDir/issuer.pem" -outform DER -out "$manualDir/private.der"
if ($LASTEXITCODE -ne 0) { throw 'PKCS8 export failed' }
& openssl pkey -in "$manualDir/issuer.pem" -pubout -outform DER -out "$manualDir/public.der"
if ($LASTEXITCODE -ne 0) { throw 'SPKI export failed' }
$privateB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$manualDir/private.der"))
$publicDer = [IO.File]::ReadAllBytes("$manualDir/public.der")
$publicB64 = [Convert]::ToBase64String($publicDer)
# Named P-256 SPKI prefix before the 65-byte uncompressed point.
$prefix = ([BitConverter]::ToString($publicDer[0..25])).Replace('-', '').ToLowerInvariant()
if ($publicDer.Length -ne 91 -or $prefix -cne '3059301306072a8648ce3d020106082a8648ce3d030107034200') {
  throw 'Unexpected SPKI; no pin exported'
}
$point = [byte[]]$publicDer[26..90]
$pin = ([BitConverter]::ToString($point)).Replace('-', '').ToLowerInvariant()
if ($pin -cnotmatch '^04[0-9a-f]{128}$') { throw 'Invalid P-256 point' }
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint = ([BitConverter]::ToString($sha.ComputeHash($point))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }
[IO.File]::WriteAllText("$manualDir/backend-issuer.properties",
  "miezmerker.issuer.private-key-pkcs8-b64=$privateB64`nmiezmerker.issuer.public-key-spki-b64=$publicB64`n")
[IO.File]::WriteAllText("$manualDir/sdkconfig.issuer.defaults", "CONFIG_MM_ISSUER_PUBLIC_KEY_HEX=`"$pin`"`n")
Write-Host "Issuer public SHA256(point)=$fingerprint"
```

Stop on any failure. Do not use OpenSSL `-text`, print secret variables, or use
Unix `base64 -w0`. PEM, private DER and properties are all secrets. Use the
properties URI in section 3 and the defaults path in section 4, replacing the
directory. The public key is derived from the private PEM; backend startup
validates the pair. Compare both runtime fingerprints before onboarding.

To move the manual pair into the normal validated, idempotent store, import it
from this same private shell (a different existing store is rejected):

```powershell
$env:ISSUER_PRIVATE_PKCS8_B64 = $privateB64
$env:ISSUER_PUBLIC_SPKI_B64 = $publicB64
try { & ./scripts/dev-issuer.ps1 -ImportFromEnvironment }
finally {
  Remove-Item Env:ISSUER_PRIVATE_PKCS8_B64, Env:ISSUER_PUBLIC_SPKI_B64
  Remove-Variable privateB64, publicB64
}
```

## 8. Reflash and deliberate rotation

For a routine reflash, reuse the same directory and pair: run setup, check the
actual sdkconfig with `-CheckFirmwarePin`, build/flash as in section 4 and compare
serial fingerprints. Never regenerate keys or erase NVS as part of a build clean.

Rotation deliberately uses a NEW directory; there is no in-place force switch:

1. Confirm the rotation decision, inventory all registered nodes and backend
   instances, stop issuance/backend processes and securely back up the old store.
2. Start a fresh shell without issuer/configuration overrides. Explicitly select
   a new path: `& ./scripts/dev-issuer.ps1 -KeyDirectory "$env:LOCALAPPDATA/MiezMerker/issuer-v2"`.
   Retain the old directory. Record the new public fingerprint.
3. Change the IntelliJ properties URI deliberately. Check UTC again before
   attesting, then restart every backend instance with the same new pair.
4. Update each existing sdkconfig pin deliberately via menuconfig; defaults do
   not override it. Validate with the new `-KeyDirectory` and `-CheckFirmwarePin`,
   rebuild and reflash every affected node without erasing NVS/ownership/data.
5. Compare every running ESP fingerprint and test registration/sync. Obtain fresh
   offline credentials: old credentials and pending artifacts signed by the old
   issuer are rejected under the new pin. Nodes retaining the old pin reject new
   proofs. Plan downtime and access to offline nodes; there is no automatic
   multi-key migration. A rollback requires coordinating backend AND firmware
   pins and must respect the reason for rotation (especially compromise).

A directory change or successful sdkconfig check does not prove fleet migration.
