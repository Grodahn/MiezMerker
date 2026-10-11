# Issue #112 verification (2026-10-11, Windows)

## Board and wiring

Configured target: ESP32-C3 SuperMini, ESP-IDF 6.1, 4 MB, native USB-Serial/JTAG.
Source/configuration audit before implementation found only GPIO9 used for the
BOOT claim/reset button, native USB on 18/19, no RFID reader/RTC/ADC using GPIO3.
ESP-IDF C3 valid GPIO mask includes 3; GPIO2/8/9 strapping, USB18/19 and flash
12-17 are excluded from the mock pin choices. GPIO3 is available in the current
firmware configuration. Physical board/pad/wire inspection is **UNVERIFIED**.

```text
internal pull-up (enabled by firmware)
          |
GPIO3 ----o  normally open pushbutton  o---- GND
open: HIGH                 pressed: LOW
```

No external voltage. Header label `3` means GPIO3, not the third physical pad.
GPIO9/BOOT must not be used for this simulator.

Current enumeration using the existing `serial_capture.py` found **no Espressif
VID 303A/PID 1001 port**. `Get-PnpDevice -PresentOnly -Class Ports` returned only
Bluetooth serial COM3 and COM4. Historical COM6 from #108 was not assumed present.
Port evidence is retained locally in `test-results/issue112/ports.txt`.
No flash, erase, factory reset, claim, synthetic USB command or device-state
mutation was performed. No device serial evidence exists for this implementation.

**Physical press/release, on-board pull-up, random captures, power-cycle recovery
and actual BLE/Android synchronization are UNVERIFIED.** The agent cannot inspect
or actuate a disconnected board/button. Hardware-free GPIO tests are automated
port samples, not physical acceptance. After reconnection: list ports, inspect
chip/MAC, use preserving `flash_board.py`, capture baseline UUID/key/store/next/ACK,
flash the explicit dev image, press/release and hold the wired button, record
`demo chip=<1..5> capture=<result> claimed=... clock=UNKNOWN attribution=UNCONFIRMED`,
then compare status/digest before/after an ordinary reset. Electrical power-cycle
must be separately observed. Do not erase or exercise the destructive BOOT reset.

## Capture and safety

The explicit dev profile and GPIO flag are both required; the default release
profile rejects development GPIO/console flags. Production ELF inspection checks
that `DevButtonReader` and `GpioButton` are absent. CI checks configuration and
reader symbol absence/presence for the two variants. The pin is configurable;
compile-time checks reject reserved strapping/BOOT choices.

The polled reader feeds exact literal demo chip strings through `Core::poll_reader`
and unchanged `Core::record_chip_read` under the board/NimBLE state mutex. Press
and release require 50 ms stable levels; there is no ISR/storage call. Held input
at startup waits for a stable release. Each press consumes exactly one attempt,
even if rejected. RNG uses `BoardRandom`/ESP32 hardware entropy with unbiased
byte rejection sampling. No BLE debug endpoint or authorization bypass is added.

Core's per-chip 2000 ms fixed deduplication window is unchanged: repeats return
`DEBOUNCED`, allocate no sequence, and do not refresh the window. A different
chip can record independently; a later same-chip read outside its window records.
A successful capture reserves the monotonic sequence before durable NVS append.
Unacknowledged records, Node UUID/key and wire bytes survive adapter reconstruction;
reboot may leave a reserved sequence gap but never reuse a sequence. Store-full
and ambiguous I/O semantics, authenticated ACK/retry and reset journal remain
covered by the existing regression tests. Local raw capture does not claim a Node
or establish deployment attribution.

## Verification results

- Focused `esp32-dev-button-reader`: PASS (1 CTest). Covers active LOW, short
  press/release bounces, stable edges, long hold, boot-held suppression, all RNG
  byte outcomes plus 255 rejection, unchanged core deduplication, persistent
  identity/log reload, unacknowledged bytes/next sequence, unclaimed capture,
  no UTC timestamp, exact chip strings in BLE codec, and failed-attempt consumption.
- Relevant root `storage|ble|auth|lifecycle`: PASS (47 CTests).
- Standalone offline-auth: PASS (1 CTest).
- Production ESP-IDF 6.1 build/size and config/ELF absence: PASS.
- Backend existing ingest: PASS (35 tests); existing visits: PASS (22 tests),
  including unknown/unattributed reads and historical deployment boundaries.
- Preserving flash-tool safety suite: PASS (4 Python tests).
- Development ESP-IDF 6.1 build/size: PASS. Production image 590928 bytes;
  development image 609664 bytes; both fit the unchanged 2 MiB app partition.
- Negative release guard: PASS (dev sdkconfig rejected with profile OFF).
  The negative check uses a separate build/configuration; no device writes.
- Config/ELF assertions verify reader absence in production and presence in dev.

Full build/test logs are local under `test-results/issue112` and the compact
runner's `test-results/compact`; successful verbose logs are not committed.

## Blocked end-to-end acceptance

The board clock remains UNKNOWN with no UTC timestamp. #4 must supply independently
trusted UTC and a cold-boot/rollback trust policy. Peer/PC upload time is not a
substitute. Offline credential `iat/exp` and signed claim timestamp validation
remain fail closed. Provision the deployment issuer public key independently
(`CONFIG_MM_ISSUER_PUBLIC_KEY_HEX`), then use the existing administrator physical
claim flow, active NodeDeployment and legitimate organization/AppDevice credentials.
The backend alone resolves FeedingSite from historical deployments. Firmware logs
`claimed=no` where applicable and always marks attribution UNCONFIRMED; it cannot
know whether a claimed Node has a valid backend deployment. UNKNOWN observations
cannot fabricate attributed visits. Android durable persistence before ACK,
lost-ACK retry and demo-cat/FeedingSite display remain blocked pending these
prerequisites and physical hardware. No organization/site IDs or timestamps are
injected. Release cleanup gate #113 remains mandatory.

## Integrated review and efficiency

One integrated review checked #112 against the reader, board wiring/config gates,
composition/core port, clock/security boundaries, persistent adapters, BLE codec,
tests, CI and documentation. A CI symbol match was corrected for the ESP32
C++ ABI tag after inspecting the real ELF. The negative release check was
isolated from the successful dev build/config to avoid an IDF cache side effect.
No production core, BLE protocol, backend, demo seed
or concurrent #93/#97 implementation surface was changed.

Initial combined source output exceeded the usual bound and one Windows glob was
invalid; later reads used established paths and focused excerpts. Tool-path and
log-path corrections were needed; use absolute paths and process-local tool PATH
from the start. A first parallel IDF configure raced on shared managed_components;
finish dependency initialization before building variants. Isolate negative
configuration tests to avoid restoring/rebuilding positive caches. Host dependency fetching
was blocked, so existing local pinned sources were reused. Sandbox restrictions
required ordinary Windows execution for IDF ELF tools and backend loopback tests.
No measured token count is claimed; one relevant domain regression and focused
repairs were used instead of repeatedly duplicating CI.
