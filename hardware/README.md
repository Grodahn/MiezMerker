# Hardware boundary

This directory will contain board schematics, BOM, wiring and power measurements.
The target is an **ESP32-C3**-based battery-powered Node and a 134.2-kHz
animal-chip RFID reader. Board/pin/reader choices and power-budget measurements
are deferred to hardware tickets. No fabricated schematic or pin assignment is
part of #2. Hardware communicates only through the ports in firmware-core,
whose capture/identity/clock/storage contracts are defined by issue #5
(`firmware-core/README.md`, ADR 0012).
