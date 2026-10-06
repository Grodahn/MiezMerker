-- Issue #32: human-readable display name on the global AppUser identity.
--
-- display_name belongs to app_users, not to organization_memberships: the same
-- person keeps one name across all organizations while role/status stay
-- per-membership. Nullable so pre-#32 rows survive unchanged; no names are
-- derived from email prefixes. Passwords, statuses and timestamps untouched.
-- UI falls back to email until a real name is provided.
--
-- Compatible with PostgreSQL and the H2 test setup (MODE=PostgreSQL).

ALTER TABLE app_users ADD COLUMN display_name VARCHAR(255);
