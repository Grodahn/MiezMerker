# 0005 — React, TypeScript, Vite, Dexie and a Service Worker
Status: Accepted.

Use React/TypeScript/Vite for the shared PWA, Dexie for IndexedDB schema migration
and local outbox storage, and a generated Service Worker for shell precaching.
Wrap Web Bluetooth, Web Crypto, local persistence and worker registration behind
frontend interfaces/adapters. No offline mirror of all management data is needed.
Pinned compatible stable dependency versions and the lockfile make builds repeatable.
