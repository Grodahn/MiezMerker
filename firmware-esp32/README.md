# ESP32-C3 SuperMini board port (#108)

`idf/` is a flashable **ESP-IDF 6.1 / ESP32-C3 / 4 MB** application. Root
CMake/CTest still builds the portable core, simulator, GATT router and adapter
regressions without an ESP-IDF dependency. The application calls the existing
composition boundary and uses the same Core, NodeIdentityManager, SyncServer,
GattRouter, codec and OfflineAuthSession as the host tests. No capture,
sequence, claiming, authorization or ACK state machine is duplicated.

See [hardware evidence](hardware/issue108.md) for measured results and the
remaining prerequisites. An advertisement or green build alone does not prove
authenticated synchronization. No RFID reader, external RTC or Wi-Fi is initialized.

## Windows build and port selection

Use an EIM-activated ESP-IDF 6.1 PowerShell terminal. `idf.py` may be an alias
for `Invoke-idfpy`; do not assume it is an executable. Alternatively
`tools/idf.ps1` configures only its process from the installed EIM paths. Its
`-IdfPath` / `-ToolsPath` options allow another installation location. Tools
must already be installed; this helper does not install or reconfigure Windows.

From the repository root:

```powershell
Get-PnpDevice -PresentOnly -Class Ports | Select-Object Status,FriendlyName,InstanceId
Get-PnpDevice -PresentOnly -Class Bluetooth | Select-Object Status,FriendlyName
$idfPython = 'C:\Espressif\tools\python\v6.1\venv\Scripts\python.exe'
& $idfPython firmware-esp32/tools/serial_capture.py
# Select a present Espressif USB device, then verify chip/model/revision/MAC.
& $idfPython -m esptool --port COM6 chip-id
```

`COM6` below is an **example**, not a default. Do not flash a Bluetooth serial
port or assume a disconnected/stale USB entry is a second board. If port open
fails, close the specific serial monitor using it; do not kill unrelated
Python/terminal processes. Re-list ports after USB re-enumeration.

Production build (explicit configuration prevents accidentally reusing a dev cache):

```powershell
Push-Location firmware-esp32/idf
idf.py --version
idf.py -B build-production -D SDKCONFIG=sdkconfig.local-production set-target esp32c3
idf.py -B build-production -D SDKCONFIG=sdkconfig.local-production `
  -D 'SDKCONFIG_DEFAULTS=sdkconfig.defaults' build size
# Standard activated IDF workflow, after confirming the selected board:
idf.py -B build-production -p COM6 flash monitor
# Exit monitor with Ctrl+]. Normal flash never needs erase_flash.
Pop-Location
```

For scripted runs, the safer flash wrapper additionally checks chip MAC,
4 MB image settings, permitted image offsets and existing data-partition geometry:

```powershell
# Replace the MAC with the inspected board's hardware MAC, not its Node UUID.
& $idfPython firmware-esp32/tools/flash_board.py --port COM6 `
  --expected-mac 44:b1:76:18:ca:78 --build-dir firmware-esp32/idf/build-production
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset `
  --seconds 15 --output test-results/board-a-boot.txt
```

Equivalent helper build, without an activated terminal:

```powershell
Push-Location firmware-esp32/idf
& ../tools/idf.ps1 -IdfArguments @('-B','build-production','-D',
  'SDKCONFIG=sdkconfig.local-production','-D','SDKCONFIG_DEFAULTS=sdkconfig.defaults','build','size')
Pop-Location
```

The SuperMini can remain in ROM download mode after flashing. Output containing
`boot:0x5 (DOWNLOAD(USB/UART0))` / `waiting for download` is not a crash.
The capture helper can attempt an ordinary USB-JTAG hard reset. If still in
download mode, **release BOOT and press RESET once**. Do not repeatedly reflash.

## Durable state and limits

| Partition | Offset | Size | Purpose |
| --- | --- | --- | --- |
| nvs | 0x9000 | 24 KiB | ESP-IDF PHY/system state |
| phy_init | 0xf000 | 4 KiB | PHY |
| factory | 0x10000 | 2 MiB | Application; IDF enforces image size |
| node_state | 0x210000 | 256 KiB | UUID, P-256 scalar/public key, sequence, boot/incarnation, reset journal, ownership/receipt |
| observations | 0x250000 | 1728 KiB | Immutable log, durable high sequence and ACK cursor |

The dedicated NVS partitions survive ordinary reboot and normal application
flashing. They must retain these offsets/sizes on later firmware updates. The
flash helper refuses an incompatible established layout. No data partition is
included in the flashed image; every board creates its own UUID/keypair on
first boot. A load error never provisions replacement identity.

`PersistentIdentity` explicitly serializes bounded fields; it never memcpy's
C++ objects. `PersistentObservations` encodes records with the existing BLE
codec. Version/magic, lengths, identity/sequence consistency and a CRC guard
each blob. NVS atomically replaces a single blob; observation records, durable
high sequence and ACK are one transaction. An interrupted replacement exposes
old or new state, and a failed/ambiguous write latches storage failure until
reload. NVS initialization errors never invoke automatic erase.

For this first board port the log is a **bounded 128-record snapshot**, rewritten
on append/ACK/compaction. This deliberately trades write amplification and
capacity for a simple atomic recovery boundary, bounded RAM and clear failure
semantics. Maximum serialized log is under 17 KiB; the large partition provides
NVS garbage-collection/wear headroom. This is not an unbounded field logger or
a flash-endurance qualification. Full storage rejects capture without deleting
unacknowledged records. Only authenticated explicit compaction can free an
ACKed prefix. Core still reserves sequence before append and reconciles on boot.

Private keys are unique per board and never logged. This USB development port
uses ordinary NVS; physical flash-read resistance/secure boot/flash encryption
are not claimed, and no irreversible eFuses are burned by these instructions.
RNG entropy is enabled during initial provisioning before radio use, then the
active BLE controller supplies hardware entropy. PSA generates/signs P-256
keys; boot verifies that persisted public and private key material match.

## Clock, issuer trust and claiming

`BoardClock` reports `UNKNOWN`, no UTC epoch, and an independent monotonic
debounce clock. It never uses uptime, Windows time or peer time as UTC.
`TimeCorrect` cannot bootstrap authorization: the UTC adapter rejects it, and
the session already requires trusted UTC. Existing timestamps stay immutable.

**Secure claim/sync is currently blocked by trusted UTC**: ADR 0013 requires
a fresh node-signed claim timestamp, and ADR 0012 requires reliable `iat/exp`
checks before authorization. Supply an independently trusted UTC source with
a defined cold-boot trust/rollback policy (RTC/time initialization work in
[#4](https://github.com/Grodahn/MiezMerker/issues/4)); an unchecked PWA/PC clock
is insufficient. Then provision the deployment's public issuer key independently
using `CONFIG_MM_ISSUER_PUBLIC_KEY_HEX` (130 hex chars, uncompressed P-256
`04 || x || y`) through `idf.py menuconfig`. Obtain it through the trusted
deployment channel, never from an incoming receipt as its own trust anchor.
Empty pin fails closed. No issuer private key or shared device identity belongs
in a build image. The claim receipt's signed `ipk_*` echo must match that pin.

On a running, unclaimed board, hold BOOT/GPIO9 for **2–5 seconds and release**
to open a 120-second physical claim window. Without trusted UTC this cannot
produce a claim proof. With the prerequisites satisfied, use the existing
administrator PWA claim workflow. Initial receipt commit requires physical
mode, independently pinned ES256 verification, expiry and UUID/key binding.
Same-owner delivery remains idempotent. Reconnect/Challenge constructs the
authorizer from the committed owner. All claim characteristics are registered.

Factory reset is a separate, destructive physical action: while the application
is running, hold BOOT for **at least 12 seconds and release**, then within
30 seconds **hold it for at least 12 seconds and release again**. The second
hold must also finish within that window. A short/intermediate hold cancels
confirmation. BOOT held during startup is ignored until first release. This
uses Core's resumable reset journal: rotate UUID/key/incarnation, clear old log
and ACK, then finish the journal. Ordinary RESET and flashing do not do this.
Do not test factory reset on a node whose identity/data must be preserved.

## NimBLE transport

The service/eleven characteristic UUIDs come from the existing registry in
`ble_gatt.hpp`; properties and frames follow `protocol/ble/messages.md`.
Advertising contains the service UUID and generic `MM-Node` name, with four
technical version/flag bytes in 128-bit Service Data (Bluetooth AD type **0x21**;
the protocol document's 0x16 label denotes 16-bit Service Data). No Node ID,
organization, chip, observation or pending count is advertised. A volatile
random BLE address is independent of the durable Node UUID.

One connection is allowed. Recursive mutex serialization protects Core,
identity, NVS, auth and GATT state across the board loop and NimBLE host task.
Disconnect/host reset clears challenges, fragment assemblies, subscriptions and
cached values. Protected reads/writes reject with ATT authorization errors and
cached long-read continuations recheck expiry/authority. Inactive operations
expire after 10 seconds. Auth/receipt envelopes are restricted to their own
characteristics, use the unchanged router assembly, and retain final responses
for read. Read Blob offsets return a stable cached full frame; NimBLE slices it.

MTU is bounded to 23..517; worst-case batch pages are clamped to 1..3 records
and <=512-byte ATT values. With negotiated MTU >=185, whole page notifications
fit MTU-3. At MTU 23, use one-record long reads; no truncated notification is
sent. The existing PWA requests one record and reads responses. Large owner
metadata exceeding the ATT value bound fails safely instead of being truncated.

## Explicit synthetic input and local tests

Build dev separately; default/production has no console command parser, test
vectors or automatic capture:

```powershell
Push-Location firmware-esp32/idf
idf.py -B build-dev -D SDKCONFIG=sdkconfig.local-dev `
  -D 'SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.dev' build size
Pop-Location
# Flash the explicitly selected dev image with flash_board.py, then:
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --seconds 15 `
  --command 'synthetic 3' --command status --command crypto-test `
  --output test-results/board-a-capture.txt
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset --seconds 15 `
  --command status --output test-results/board-a-reset.txt
```

`synthetic 1..16` is bounded and local. IDs are `SYNTHETIC-MM-<reserved next
sequence>`, unmistakably test data, and pass through `Core::record_chip_read`
and actual NVS. `status` reports identity/key fingerprint, next/stored/ACK and
an immutable-log digest without printing chips/private keys. `reboot` is a
normal restart. `crypto-test` exercises separate verifier instances with public
golden vectors and a fixed *test* time, plus real device signing; it never
sets the board clock or authorizes GATT. There is no BLE injection endpoint.

For reset-during-capture recovery (does **not** prove electrical power-loss):

```powershell
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --seconds 15 `
  --command 'synthetic 16' --interrupt-after-ms 30 --output test-results/board-a-interrupt.txt
```

Reboot must retain all previously committed observations, unique monotonic
sequences and key/UUID. A gap for an interrupted reservation is allowed. Compare
status/digest across ordinary resets and generate another observation afterward.
Never count an unexecuted command as a successful persistence check.

BLE tools use an isolated environment, not a machine-wide installation:

```powershell
python -m venv build/hil-venv
& build/hil-venv/Scripts/python.exe -m pip install -r firmware-esp32/tools/requirements-hil.txt
& build/hil-venv/Scripts/python.exe firmware-esp32/tools/ble_hardware_test.py
& build/hil-venv/Scripts/python.exe firmware-esp32/tools/ble_hardware_test.py `
  --node-id '<UUID from the selected board USB log>' --output test-results/board-a-ble.json
```

The test matches the expected public Node UUID before test writes, enumerates
all characteristics, rejects unauthenticated operations and malformed credentials,
then reconnects to verify fresh challenges/security cleanup. It does not claim
authorized transfer/ACK, Android tests or two-board tests.

For Android nRF Connect: scan for the service UUID above, connect, enumerate
...5b01 through ...5b0b, read Info/Owner/Challenge, attempt Status/Batch/Ack/Time
unauthenticated (expect rejection), disconnect/reconnect and repeat. Once trusted
UTC, issuer pin, physical claiming and legitimate AppDevice credentials exist,
use the existing Android PWA collector for signed Node proof, observation transfer,
IndexedDB persistence before ACK, lost-ACK retransmission and duplicate-free retry.
Do not disable auth to complete that checklist.

## Second board and hardware-independent verification

Board B is not assumed connected. Repeat port listing/chip identification,
record its hardware MAC, select its own COM port, and use the same flash/capture
scripts with **that** MAC/Node UUID and separate output paths. First boot must
show a different Node UUID/key fingerprint and independent next sequence/store.
Do not copy NVS images between boards. Generic BLE names are intentionally the
same; test selection uses each UUID, not names/MAC as application identity.
This prepares real multi-node tests in #76 without changing PWA orchestration.

Host regression (configure/build first):

```powershell
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
python scripts/test.py firmware
cmake -S firmware-esp32/tests -B build/offline-auth -DCMAKE_BUILD_TYPE=Release
cmake --build build/offline-auth --parallel
python scripts/test.py firmware --build-dir build/offline-auth --label auth
```

`esp32-board-adapters` covers serialization, old/new ambiguous commits,
reserve/append failure, reboot, bounded/full storage, ACK/compaction failure,
reset recovery, malformed state, MTU bounds and cached authorization. These
are host fault models, not measurements of flash power interruption. Existing
Root/CI protocol, simulator/auth/lifecycle regressions remain intact. The new
ESP32 CI builds production and dev with a pinned IDF 6.1 image and checks
configuration/image/partition sizes; GitHub-hosted CI has no USB/BLE access.
