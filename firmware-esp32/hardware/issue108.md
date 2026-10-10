# Issue #108: local ESP32-C3 hardware evidence

Measured on **2026-10-10**, Windows, with a physical ESP32-C3 SuperMini connected
through native USB-Serial/JTAG. These are MiezMerker firmware measurements;
previous Hello World/bleprph demonstrations are not counted. No Android test,
electrical power removal, Board B test or authorized observation transfer was
performed. No full-flash erase or factory reset was performed.

## Device, software and image

- Board A: esptool detected **ESP32-C3 AZ (QFN32), revision v1.1**, 160 MHz,
  40 MHz crystal, embedded **4 MB XMC flash**. Confirmed port **COM6**, chip
  MAC `44:b1:76:18:ca:78`. COM3/4 were Bluetooth serial ports; disconnected
  COM5 was not treated as Board B. Port open succeeded; no monitor needed killing.
- Windows adapter: **Intel(R) Wireless Bluetooth(R)**, Microsoft LE enumerator.
  Bleak 3.0.2 exercised real WinRT GATT, with pyserial 3.5 for USB capture.
  Libraries installed in a repository-local `build/hil-venv`, not globally.
- SDK: `C:\esp\v6.1\esp-idf`, ESP-IDF **v6.1 / 6.1.0**, EIM Python
  `C:\Espressif\tools\python\v6.1\venv\Scripts\python.exe`.
  Initial sandboxed CIM access failed; normal Windows execution with the
  authorized sandbox escalation accessed both USB and Bluetooth successfully.
- Final flashed application source: **`c165ce5`**, version **`mm108-c165ce5`**,
  explicit dev configuration. Later evidence/tool-check changes do not alter
  that firmware implementation. Earlier bring-up captures identify the working
  tree as `mm108-7476426-dirty`; their ELF hashes distinguish those increments.
- Final `.bin`: **607536 bytes (`0x94530`)**, application partition `0x200000`,
  **71% free**. Bootloader `0x52f0`, **35% free** before partition-table offset.
  IDF linker/partition/image checks passed. Size report: static DRAM **86453 B**
  (**26.91%**, reported available region **321296 B**). Observed final idle heap
  about **166 KB**; this is not a worst-case RAM/endurance qualification.
- Final ELF SHA-256:
  `da7218513965c1598426ab35d48eb34186c951e90e9343865e9a2bc5b3f37a94`.
- Final BIN SHA-256:
  `6daf524ec1d4fc2646621def537fde4d553526700541611ae5fd36867716881b`.
- Durable Node UUID: **`2de13b24-e393-4e28-bfaf-3f1b48950e3a`**;
  public-key fingerprint prefix **`3b16a9978a075b95`** throughout reboots/updates.
  Every boot also derives the public key from the stored private scalar and
  checks equality; no private key material is logged or committed.

## Actual commands

Commands ran from repository root unless noted; variables abbreviate the exact
installed interpreter, not a different remote environment:

```powershell
Get-PnpDevice -Class Ports | Select-Object Status,FriendlyName,InstanceId
Get-PnpDevice -Class Bluetooth | Select-Object Status,FriendlyName
Get-CimInstance Win32_Process # inspected python/idf/terminal processes
$idfPython = 'C:\Espressif\tools\python\v6.1\venv\Scripts\python.exe'
& $idfPython 'C:\esp\v6.1\esp-idf\tools\idf.py' --version
& $idfPython -m esptool --port COM6 chip-id

# In firmware-esp32/idf, helper sets process-local EIM paths/version/tool bins:
& ../tools/idf.ps1 build
& ../tools/idf.ps1 -IdfArguments @('-D','SDKCONFIG=sdkconfig.local-dev','-D',
  'SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.dev','reconfigure','build')
& ../tools/idf.ps1 reconfigure build size # final committed-source build

# Back at repository root; repeated only for distinct implementation increments:
& $idfPython firmware-esp32/tools/flash_board.py --port COM6 `
  --expected-mac 44:b1:76:18:ca:78 --build-dir firmware-esp32/idf/build
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset --seconds 15
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset --seconds 15 `
  --command 'synthetic 3' --command status --command crypto-test
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset --seconds 12 --command status
# First interruption attempt: all 16 captures finished before reset took effect.
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --seconds 15 `
  --command 'synthetic 16' --interrupt-after-ms 30
# Longer queue was actually interrupted (not all commands/records completed):
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --seconds 15 `
  --command 'synthetic 16' --command 'synthetic 16' --command 'synthetic 16' --interrupt-after-ms 1
# Final flash followed by capture:
& $idfPython firmware-esp32/tools/serial_capture.py --port COM6 --reset --seconds 15 `
  --command status --command 'synthetic 1' --command crypto-test
& build/hil-venv/Scripts/python.exe firmware-esp32/tools/ble_hardware_test.py `
  --node-id 2de13b24-e393-4e28-bfaf-3f1b48950e3a --output firmware-esp32/hardware/evidence/final-ble.json
```

The early minimal milestone used the generated `@flash_args` directly with
`python -m esptool --chip esp32c3 --port COM6 --before default-reset --after
hard-reset write-flash @flash_args` from the IDF build directory, before the
safe flash wrapper was added. All later flashes used that wrapper. Bootloader,
partition table and application were hash-verified; no identity/observation
partition image was flashed.

## Expected versus observed

| Check | Expected | Observed / status |
| --- | --- | --- |
| Early minimal build/flash | ESP32-C3 application executes before full integration | **PASS**: native USB reset, `SPI_FAST_FLASH_BOOT`, `app_main`, stable heartbeats; [early reset](evidence/early-reset.txt) |
| Production defaults | Core/NVS/NimBLE start, no synthetic input | **PASS**: boot initialized unique UUID/key, registered 11 characteristics, advertising, stable heap; no dev console parser enabled; [production boot](evidence/production-boot.txt) |
| Boot/reset | Normal boot with BOOT released, no reflashing needed for reset | **PASS**: all recorded resets booted application. No manual RESET was necessary in this run |
| BLE advertisement | Exact service, technical-only data | **PASS**: `6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c`, name `MM-Node`, service data `01000000`; [production BLE](evidence/production-ble.json), [final BLE](evidence/final-ble.json) |
| GATT/lifecycle | Eleven correct UUID/property mappings, fresh challenge on reconnect | **PASS**: actual WinRT connections, MTU **517**, all eleven registered, disconnect/reconnect and fresh 32-byte challenge checks passed |
| Unauthorized access | Protected reads/writes fail with no records/counts | **PASS**: NodeProof/Status/Batch/Ack/Time reads, relevant writes rejected by ATT; invalid fragmented Auth and invalid receipt rejected; final run had **31 persisted records**, still inaccessible |
| Physical/trusted-clock claim gate | No proof without prerequisites | **PASS** on final GATT test: ClaimAdvertisement read returned `INVALID_STATE`; no ownership committed |
| Synthetic input | Real Core reservation + NVS append, UNKNOWN timestamps | **PASS**: three captures returned `RECORDED` (`result=0`), `stored=3`, `next=4`, `ack=0`, `clock=UNKNOWN`; [capture](evidence/dev-capture.txt) |
| Reboot persistence | UUID/key, records, immutable timestamps/bytes and next sequence unchanged | **PASS**: next boot kept `stored=3`, `next=4`, same immutable log digest **`3f6810dc721f9d17`**; [reset](evidence/dev-reset.txt) |
| Reset during active capture | Old records survive; reservation may leave a gap, never reuse | **PASS** for MCU-reset recovery: from 19 records / next 20, queued 48; only 11 additional commits survived, giving **30 records / next 32**. Sequence **31** was reserved but not appended; [active interruption](evidence/dev-interrupt-active.txt) |
| Normal firmware update after interruption | No implicit factory reset or data loss | **PASS**: flash of `mm108-c165ce5` retained same UUID/key, **30 records / next 32**, digest **`95740ca1e612b1a0`** unchanged; [final flash](evidence/final-flash.txt), [final boot](evidence/final-boot.txt) |
| Capture after recovery | Next allocation continues beyond reserved gap | **PASS**: one capture allocated **32**, leaving **31 records / next 33**, digest **`148a1df6a233657c`**; [final boot](evidence/final-boot.txt) |
| Final reset with completed image | Identity, records and sequence remain durable | **PASS**: boot 13 retained **31 records / next 33**, ACK 0 and the same digest **`148a1df6a233657c`**; storage ready, stable idle heap 166392 B; [final reset](evidence/final-reset.txt) |
| Real-board cryptography | Current PSA/Mbed TLS 4 verifies existing public vectors and persisted device signing | **PASS**: `crypto-test` passed golden ES256 credential/PoP/receipt, missing clock, expiry/future time, wrong proof/key, foreign org/replay and device keypair/signature tests. Separate test verifier/fixed time never authorize production GATT |
| USB power removal / flash write boundary | Survive genuine electrical interruption | **UNVERIFIED**: no remotely controlled power switch; reset test is not electrical power removal and not every NVS instruction boundary |
| Authorized PWA transfer / ACK / lost ACK | Real durable client persistence before ACK and retransmission | **UNVERIFIED**, fail closed: independently trusted UTC, deployment issuer pin, legitimate physical claim/owner and AppDevice credentials required |
| Android, small physical MTU, Board B, factory reset | Separate physical acceptance | **UNVERIFIED**: not exercised; instructions in README. Factory reset intentionally not performed on the preserved node |

USB reset was observed during allocator activity (`Saved PC=0x4038e2c8` in
the interrupted image); no exact flash-write instruction is inferred. The
reserved gap and recovered durable records are the evidence. PHY subsequently
recalibrated its separate system NVS; the node partitions remained healthy.
The first 30 ms reset attempt completed all 16 writes and is retained as
[evidence of that limitation](evidence/dev-interrupt.txt), not labelled a torn-write test.

Board A was left running the explicit development image with those 31 synthetic
records, UNKNOWN clock and no ownership. No serial monitor was left open.

## Hardware-independent verification

- Root host CMake/Ninja, C++20, existing Windows llvm-mingw compiler: **PASS**.
  `python scripts/test.py firmware --build-dir build/host`: **55 tests passed**,
  including core capture/identity, BLE fixtures, auth/lifecycle, sequence/ACK,
  simulator recovery and new persistent adapter fault injection.
- Standalone offline-auth build against pinned Mbed TLS 3.6.2/cJSON 1.7.19:
  `python scripts/test.py firmware --build-dir build/offline-auth --label auth`:
  **1 test passed**, including real signed claim-receipt vectors and rejection
  checks. Existing credential expectations retained.
- `python scripts/test.py frontend src/collector/ble-codec.test.ts
  src/collector/protocol.test.ts src/collector/authorization.test.ts
  src/collector/web-bluetooth.test.ts src/collector/provision-node.test.ts`:
  **38 tests passed**.
- Production and explicit dev IDF 6.1 images built locally and flashed on Board A;
  partition bounds and size reports passed. Python hardware tooling syntax and
  `git diff --check` passed. New CI independently builds both configurations
  from a verified, digest-pinned ESP-IDF 6.1 container.

Backend/PWA features and firmware-core business rules are unchanged. Broader
PostgreSQL/end-to-end checks remain in existing GitHub CI. No hardware result
above is inferred from a host fake or GitHub-hosted runner.

## Remaining prerequisites

The first board has no trusted UTC source. **Both initial claim freshness and
offline `iat/exp` verification require independent trusted UTC**, so even a
legitimate PWA credential cannot make the current cold-boot adapter authorize.
Implement the trusted-time/RTC bootstrap and rollback policy in
[#4](https://github.com/Grodahn/MiezMerker/issues/4), independently pin the
deployment issuer public key, then perform administrator physical claiming and
actual Android PWA persistence/ACK/retry tests. This port never uses peer/PC
time or a test credential to bypass that prerequisite.

Connect Board B separately, inspect its chip/COM/MAC, flash the same application
without NVS cloning and verify its independent UUID/key/store/sequence using
the same scripts and separate logs. Multi-node PWA orchestration remains
[#76](https://github.com/Grodahn/MiezMerker/issues/76). Physical reader work
remains [#3](https://github.com/Grodahn/MiezMerker/issues/3).
