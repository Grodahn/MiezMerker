# Claim blocked by service discovery

## Observed ESP32-C3 report

After the #131 merge, a freshly flashed node established a BLE connection, but
the PWA reported `Service-Suche zeitüberschritten`. This timeout is in
`getPrimaryService`, before public identity, signed time bootstrap, claim, or
organization assignment. The reported NimBLE unsupported-remote-feature log
alone does not establish the cause. #131 did not change the GATT service or
characteristic registration. Physical cause and recovery remain unverified.

## Bounded PWA recovery

On a service discovery timeout, close the connection and wait for the existing
bounded disconnect handling, then reconnect the same user-permitted device
once. A second timeout ends the operation. Cancellation during discovery or
teardown prevents a reconnect. No additional chooser, scanning, or claim is
initiated. Identity verification, signed time, authorization, and explicit
claim confirmation still run normally after discovery succeeds.

The UI identifies a service-discovery failure and suggests closing the PWA,
toggling phone Bluetooth, restarting the node, and retrying. Backend time
service failures are separately classified; they must not suggest that the
node is unreachable.

## Verification

Automated coverage includes recovery on the first service timeout, bounded
failure on the second, cancellation, backend time-service failure classification,
and the guided browser registration after a simulated discovery timeout.
Mocks do not prove ESP32-C3/phone interoperability.

On the affected phone and node, retry after closing the PWA, toggling Bluetooth,
and restarting the node. Preserve NVS and keys; no factory reset is needed for
this check. Record whether discovery succeeds, the exact next error if any,
and whether explicit registration leaves both firmware and backend claimed in
the expected organization. If discovery still times out, retain the firmware
startup log and phone/browser versions for further GATT diagnosis.
