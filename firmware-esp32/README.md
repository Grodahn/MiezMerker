# ESP32 boundary

ESP-IDF (C++) is the selected future board framework (ADR 0001). The host-buildable
composition library links the real firmware-core. No GPIO, flash, GATT or board
entry point exists yet. Subsequent firmware work adds an ESP-IDF application here,
implements the core ports and calls `esp32::initialize(core)` from `app_main`.
The root CMake build verifies this composition boundary without an ESP32 SDK;
it does not produce a flashable image.
