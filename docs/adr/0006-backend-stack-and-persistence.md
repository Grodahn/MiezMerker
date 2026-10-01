# 0006 — Spring backend and PostgreSQL
Status: Accepted.

Use Java 21+, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL, Flyway and
Springdoc OpenAPI. Flyway owns migrations; Hibernate validates rather than creates
the schema. Raw observations are immutable/idempotently ingested primary data;
visits are derived server-side. Versioned historical NodeDeployment intervals
preserve site attribution when Nodes move. No speculative domain tables in #2.
H2 is a test-only fallback; CI also exercises PostgreSQL.
