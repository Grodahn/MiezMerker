# ESP32 boundary (target: ESP32-C3)

ESP-IDF (C++) is the selected future board framework (ADR 0001); the concrete
target chip is the **ESP32-C3**. The host-buildable composition library links
the real firmware-core, which stays free of any ESP32-specific API (see
`firmware-core/README.md` and ADR 0012). No GPIO, RTC, flash/NVS, RfidReader or
GATT adapter exists yet. Subsequent firmware work adds an ESP-IDF application
here, implements the core ports (Clock/RTC, ObservationStore and
DeviceIdentityStore on C3 flash/NVS/filesystem, RFID input) and calls
`esp32::initialize(core)` from `app_main`. The root CMake build verifies this
composition boundary without an ESP32 SDK; it does not produce a flashable
image.
