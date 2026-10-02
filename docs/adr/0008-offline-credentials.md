# 0008 — Per-AppDevice asymmetric identity and offline BLE authority
Status: Accepted trust boundary; credential/key design belongs to #17.

Each PWA installation has a local AppDevice key pair via Web Crypto. Backend
issues scoped, bounded offline credentials; Node verifies issuer trust and proof
of the AppDevice private key without Internet. Never distribute a shared
Organization secret to employees. HTTP login/session and BLE authorization are
different mechanisms. Algorithms, secure key persistence, clock validity,
challenge replay protection, revocation and credential refresh must be finalized
in #17; #2 contains only an unconfigured key interface, no cryptographic flow.
