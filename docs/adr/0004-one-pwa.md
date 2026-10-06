# 0004 — One PWA for collector and management
Status: Accepted.

There is exactly one frontend project in app. Collector #8 and management #11
are feature boundaries within this application, not separate builds/deployments.
Routes share the shell; collector-only platform adapters and persistence remain
isolated from management. This avoids two identities/clients/offline stores and
preserves a coherent phone/desktop experience.

## Follow-up: Epic #30

The original #11 combined-PWA decision above is retained as history. After #30,
there is still one React field PWA (`/login`, `/sync`, `/nodes`, `/cats`), while
Spring MVC/Thymeleaf owns `/admin/**`. Cats remains available in both surfaces;
Members, Site master-data, Observations and standalone Visits use Admin.
Both surfaces share AppUser/password/session authentication. See
[the final architecture](../issue38-field-pwa.md).