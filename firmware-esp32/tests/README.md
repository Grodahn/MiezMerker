# Offline credential interoperability

Build and run the actual ESP-IDF component on a host without an ESP-IDF installation:

```
cmake -S firmware-esp32/tests -B build/offline-auth -DCMAKE_BUILD_TYPE=Release
cmake --build build/offline-auth --parallel
ctest --test-dir build/offline-auth --output-on-failure
```

The standalone test project fetches checksum-pinned Mbed TLS 3.6.2 and cJSON 1.7.19.
Existing sources can be supplied through `MBEDTLS_SOURCE_DIR` and `CJSON_SOURCE_DIR`.
Assertions remain enabled in Release builds. Tests verify the committed fixture and
locally signed negative claims. The `.github/workflows/offline-auth.yml` job runs them.

Board applications can add `firmware-esp32/components` to `EXTRA_COMPONENT_DIRS`.
Each BLE connection must own a session, fill `begin`'s challenge with the board CSPRNG,
and call `authorize` with that connection's response. Do not accept client-provided
challenge or clock values. Every protected operation requires `can_sync(trusted_utc)`;
link teardown calls `disconnect`. Durable issuer/owner provisioning and GATT transport
remain the board and BLE adapter responsibilities.
