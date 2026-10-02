# 0010 — Android Chromium collector with offline shell
Status: Accepted.

Collector targets Android Chrome/current Chromium with Web Bluetooth, secure
context and explicit user permission/gesture. A native Android app is outside
the MVP; other browsers may use management without collector support. Precache
the shared shell and collector assets; persist collector outbox via Dexie.
Node sync must work without Internet; backend upload waits independently. First
install needs network. HTTP API data is not Service Worker cached. Capability
fallback UX, transfer-aware updates and account-switch cleanup belong to #8/auth.
