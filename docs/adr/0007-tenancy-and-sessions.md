# 0007 — Organization membership and backend session authentication
Status: Accepted boundary; actual login and roles/statuses belong to #16.

User is the global email/password login identity; OrganizationMembership links
it to a tenant. Role and membership status are separate. Backend authorization
requires active membership and server-side permissions on every business access,
including uploads and historical ownership. Client tenant IDs are not authority.
Prefer same-origin HTTP sessions with protected cookies and CSRF checks. Public
foundation endpoints are limited to health, version and OpenAPI; other paths deny
access until auth/domain policies exist. No cross-origin cookie/CORS special case.
