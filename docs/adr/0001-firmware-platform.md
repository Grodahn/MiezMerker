# 0001 — Portable C++ core, ESP-IDF board layer
Status: Accepted (framework direction; board setup follows in firmware work).

Use C++20 and CMake for a hardware-independent firmware-core. Select ESP-IDF for
the ESP32 platform application and keep every SDK/GPIO/flash/radio call in
firmware-esp32 adapters. Clock/Storage/RfidReader/BleTransport ports are the boundary.
The simulator and ESP32 composition link the same core library. This permits
hardware-free CI; #2 supplies a host composition target, not a flashable image.
