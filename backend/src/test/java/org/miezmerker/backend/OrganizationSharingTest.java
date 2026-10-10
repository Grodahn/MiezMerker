package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.ShareScope;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #96 (ADR 0016): default-deny organization visibility,
 * tenant-safe sharing policies, scope prerequisites, revocation and audit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("auth")
class OrganizationSharingTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired SharePolicyService shares;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    Organization orgA, orgB, orgC;
    AppUser adminA, memberA, adminB, adminC;
    final String password = "supersecret-password-96";

    @BeforeEach
    void http() {
        cookies = new CookieManager();
        client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    String csrfToken() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/csrf")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body()).get("token").asText();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> put(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> patch(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("X-XSRF-TOKEN", csrfToken())
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    void login(String email) throws Exception {
        var res = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    void seed() {
        cleaner.clean();
        orgA = organizations.save(new Organization("org-a", "Org A", null));
        orgB = organizations.save(new Organization("org-b", "Org B", null));
        orgC = organizations.save(new Organization("org-c", "Org C", null));
        adminA = users.save(new AppUser("admin-a@example.org", passwords.encode(password)));
        memberA = users.save(new AppUser("member-a@example.org", passwords.encode(password)));
        adminB = users.save(new AppUser("admin-b@example.org", passwords.encode(password)));
        adminC = users.save(new AppUser("admin-c@example.org", passwords.encode(password)));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgC, adminC, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
    }

    String policies(UUID org) {
        return "/api/v1/organizations/" + org + "/share-policies";
    }

    String policy(UUID org, ShareScope scope) {
        return policies(org) + "/" + scope;
    }

    String allowlist(UUID owner, UUID recipient) {
        return "{\"audience\":\"ALLOWLIST\",\"recipientIds\":[\"" + recipient + "\"]}";
    }

    void discover(UUID org, String adminEmail) throws Exception {
        login(adminEmail);
        var res = patch("/api/v1/organizations/" + org + "/visibility", "{\"discoverable\":true}");
        assertEquals(200, res.statusCode(), res.body());
    }

    JsonNode directory() throws Exception {
        login("admin-a@example.org");
        var res = get("/api/v1/organizations/directory");
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    boolean directoryContains(JsonNode directory, UUID org) {
        for (JsonNode entry : directory) {
            if (UUID.fromString(entry.get("id").asText()).equals(org)) {
                return true;
            }
        }
        return false;
    }

    int auditCount(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM organization_share_audit WHERE " + where, Integer.class);
    }

    @Test
    void discoverableColumnDefaultsToFalseAndNewOrganizationsStayHidden() throws Exception {
        seed();
        // Migration default: NOT NULL DEFAULT FALSE; existing and new rows hidden.
        // Portable across H2 (uppercases unquoted identifiers, default 'FALSE')
        // and PostgreSQL (keeps lowercase, default ''false'::boolean').
        String defaultValue = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns"
                        + " WHERE lower(table_name) = 'organizations'"
                        + " AND lower(column_name) = 'discoverable'",
                String.class);
        assertNotNull(defaultValue);
        assertTrue(defaultValue.toLowerCase(java.util.Locale.ROOT).contains("false"),
                "discoverable must default to FALSE, got: " + defaultValue);
        assertEquals("NO", jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns"
                        + " WHERE lower(table_name) = 'organizations'"
                        + " AND lower(column_name) = 'discoverable'",
                String.class));
        assertFalse(orgA.isDiscoverable());
        assertFalse(orgB.isDiscoverable());

        // A newly created organization is private, unlisted and without policies.
        login("admin-a@example.org");
        var created = post("/api/v1/organizations",
                "{\"slug\":\"org-new\",\"displayName\":\"Org New\"}");
        assertEquals(200, created.statusCode(), created.body());
        assertFalse(mapper.readTree(created.body()).get("discoverable").asBoolean());
        assertTrue(mapper.readTree(get("/api/v1/organizations/directory").body()).isEmpty());
        assertTrue(mapper.readTree(get(policies(orgA.getId())).body()).isEmpty());
    }

    @Test
    void directoryRequiresAuthenticationAndExposesOnlyDiscoverableActiveOrganizations() throws Exception {
        seed();
        assertEquals(401, get("/api/v1/organizations/directory").statusCode());

        login("admin-b@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
        login("admin-c@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgC.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());

        JsonNode dir = directory();
        assertFalse(directoryContains(dir, orgA.getId()), "hidden org must not be listed");
        assertTrue(directoryContains(dir, orgB.getId()), "discoverable org must be listed");
        assertTrue(directoryContains(dir, orgC.getId()), "discoverable org must be listed");

        // A discoverable but disabled organization disappears from the directory.
        orgC.setStatus(OrganizationStatus.DISABLED);
        organizations.save(orgC);
        assertFalse(directoryContains(directory(), orgC.getId()));
    }

    @Test
    void discoverabilityAloneNeverAuthorizesReadingForeignData() throws Exception {
        seed();
        login("admin-b@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());

        // Discoverability puts B in the directory, but grants no data access:
        // requests stay behind the strict tenant boundary.
        login("admin-a@example.org");
        assertEquals(403, get("/api/v1/organizations/" + orgB.getId() + "/cats").statusCode());
        assertEquals(403, get("/api/v1/organizations/" + orgB.getId() + "/feeding-sites").statusCode());
        assertTrue(shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()).isEmpty());
        login("member-a@example.org");
        assertEquals(403, get("/api/v1/organizations/" + orgB.getId() + "/cats").statusCode());
    }

    @Test
    void ownerAdminGrantsCareToAllowlistedRecipient() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        login("admin-b@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
        var granted = put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(200, granted.statusCode(), granted.body());
        JsonNode view = mapper.readTree(granted.body());
        assertEquals("CARE", view.get("scope").asText());
        assertEquals("ALLOWLIST", view.get("audience").asText());
        assertEquals(1, view.get("revision").asInt());
        assertEquals(orgA.getId().toString(), view.get("recipients").get(0).get("id").asText());

        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // The owner sees its outgoing policy with the recipient.
        var list = get(policies(orgB.getId()));
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(1, mapper.readTree(list.body()).size());

        // The recipient has no outgoing grants of its own.
        login("admin-a@example.org");
        assertTrue(mapper.readTree(get(policies(orgA.getId())).body()).isEmpty());
    }

    @Test
    void onlyActiveAdminsManagePoliciesAndVisibility() throws Exception {
        seed();
        login("member-a@example.org");
        assertEquals(403, get(policies(orgA.getId())).statusCode());
        assertEquals(403, put(policy(orgA.getId(), ShareScope.CARE),
                "{\"audience\":\"PRIVATE\"}").statusCode());
        assertEquals(403, delete(policy(orgA.getId(), ShareScope.CARE)).statusCode());
        assertEquals(403, patch("/api/v1/organizations/" + orgA.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());

        // A's admin cannot reach B's policies through A's organization path.
        login("admin-a@example.org");
        assertEquals(403, get(policies(orgB.getId())).statusCode());
        assertEquals(403, put(policy(orgB.getId(), ShareScope.CARE),
                "{\"audience\":\"PRIVATE\"}").statusCode());

        // PENDING and DISABLED memberships manage nothing, even with an ADMIN role.
        AppUser pendingB = users.save(new AppUser("pending-b@example.org", passwords.encode(password)));
        memberships.save(new OrganizationMembership(orgB, pendingB, MembershipRole.ADMIN, MembershipStatus.PENDING));
        AppUser staleB = users.save(new AppUser("stale-b@example.org", passwords.encode(password)));
        OrganizationMembership stale = new OrganizationMembership(orgB, staleB,
                MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        memberships.save(stale);
        stale.disable();
        memberships.save(stale);
        for (String email : List.of("pending-b@example.org", "stale-b@example.org")) {
            login(email);
            assertEquals(403, get(policies(orgB.getId())).statusCode(), email);
            assertEquals(403, put(policy(orgB.getId(), ShareScope.CARE),
                    "{\"audience\":\"PRIVATE\"}").statusCode(), email);
            assertEquals(403, delete(policy(orgB.getId(), ShareScope.CARE)).statusCode(), email);
            assertEquals(403, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                    "{\"discoverable\":true}").statusCode(), email);
        }

        // B's admin manages B's own visibility.
        login("admin-b@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
    }

    @Test
    void allowlistRejectsUnknownHiddenSelfOrDuplicatedRecipients() throws Exception {
        seed();
        login("admin-b@example.org");
        String unknown = UUID.randomUUID().toString();
        for (String body : List.of(
                allowlist(orgB.getId(), orgC.getId()),
                allowlist(orgB.getId(), orgB.getId()),
                "{\"audience\":\"ALLOWLIST\",\"recipientIds\":[\"" + unknown + "\"]}",
                "{\"audience\":\"ALLOWLIST\",\"recipientIds\":[\"" + orgA.getId() + "\",\"" + orgA.getId() + "\"]}",
                "{\"audience\":\"ALLOWLIST\",\"recipientIds\":[null]}",
                "{\"audience\":\"ALLOWLIST\"}")) {
            var res = put(policy(orgB.getId(), ShareScope.CARE), body);
            assertEquals(400, res.statusCode(), res.body());
        }
        // Audience/recipient combination rules.
        for (String body : List.of(
                "{\"audience\":\"PRIVATE\",\"recipientIds\":[\"" + orgA.getId() + "\"]}",
                "{\"audience\":\"ALL_DISCOVERABLE\",\"recipientIds\":[\"" + orgA.getId() + "\"]}")) {
            var res = put(policy(orgB.getId(), ShareScope.CARE), body);
            assertEquals(400, res.statusCode(), res.body());
        }
        // A discoverable, ACTIVE recipient is accepted.
        login("admin-a@example.org");
        assertEquals(200, patch("/api/v1/organizations/" + orgA.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
        login("admin-b@example.org");
        assertEquals(200, put(policy(orgB.getId(), ShareScope.CARE),
                allowlist(orgB.getId(), orgA.getId())).statusCode());
    }

    @Test
    void visibilityAndGrantsAreSeparateConcepts() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        // B stays hidden but may still prepare a grant; it has no effect while hidden.
        login("admin-b@example.org");
        assertEquals(200, put(policy(orgB.getId(), ShareScope.CARE),
                allowlist(orgB.getId(), orgA.getId())).statusCode());
        assertTrue(shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()).isEmpty());

        // Making the same organization discoverable activates the stored grant
        // without any new grant call: visibility and permission are independent.
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
    }

    @Test
    void noReciprocityAndNoTransitivity() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        discover(orgB.getId(), "admin-b@example.org");
        login("admin-b@example.org");
        put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // Reciprocity: B→A does not imply A→B.
        assertTrue(shares.resolveEffectiveScopes(orgB.getId(), orgA.getId()).isEmpty());

        // Transitivity: A→B and B→C do not imply A→C.
        login("admin-a@example.org");
        put(policy(orgA.getId(), ShareScope.CARE), allowlist(orgA.getId(), orgB.getId()));
        discover(orgC.getId(), "admin-c@example.org");
        login("admin-c@example.org");
        put(policy(orgC.getId(), ShareScope.CARE), allowlist(orgC.getId(), orgB.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgB.getId(), orgC.getId()));
        assertTrue(shares.resolveEffectiveScopes(orgA.getId(), orgC.getId()).isEmpty());
    }

    @Test
    void scopePrerequisitesAreEnforcedAtResolution() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");

        // VISITS, SITE_LABEL and PHOTO without CARE are never effective.
        put(policy(orgB.getId(), ShareScope.VISITS), allowlist(orgB.getId(), orgA.getId()));
        put(policy(orgB.getId(), ShareScope.SITE_LABEL), allowlist(orgB.getId(), orgA.getId()));
        put(policy(orgB.getId(), ShareScope.PHOTO), allowlist(orgB.getId(), orgA.getId()));
        assertTrue(shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()).isEmpty());

        // Remove them again: CARE alone is effective.
        delete(policy(orgB.getId(), ShareScope.VISITS));
        delete(policy(orgB.getId(), ShareScope.SITE_LABEL));
        delete(policy(orgB.getId(), ShareScope.PHOTO));
        put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // CARE + VISITS unlocks VISITS.
        put(policy(orgB.getId(), ShareScope.VISITS), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE, ShareScope.VISITS),
                shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // SITE_LABEL stays ineffective while VISITS is missing.
        delete(policy(orgB.getId(), ShareScope.VISITS));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
        put(policy(orgB.getId(), ShareScope.VISITS), allowlist(orgB.getId(), orgA.getId()));
        put(policy(orgB.getId(), ShareScope.SITE_LABEL), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL),
                shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // PHOTO needs only CARE.
        put(policy(orgB.getId(), ShareScope.PHOTO), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL, ShareScope.PHOTO),
                shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
    }

    @Test
    void allDiscoverableAudienceGrantsOnlyToActiveDiscoverableOrganizations() throws Exception {
        seed();
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        assertEquals(200, put(policy(orgB.getId(), ShareScope.CARE),
                "{\"audience\":\"ALL_DISCOVERABLE\"}").statusCode());

        // A is hidden: no access despite the open audience.
        assertTrue(shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()).isEmpty());

        login("admin-a@example.org");
        patch("/api/v1/organizations/" + orgA.getId() + "/visibility", "{\"discoverable\":true}");
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // A hidden organization never matches ALL_DISCOVERABLE.
        assertTrue(shares.resolveEffectiveScopes(orgC.getId(), orgB.getId()).isEmpty());

        // A discoverable but disabled recipient loses access at query time.
        login("admin-c@example.org");
        patch("/api/v1/organizations/" + orgC.getId() + "/visibility", "{\"discoverable\":true}");
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgC.getId(), orgB.getId()));
        orgC.setStatus(OrganizationStatus.DISABLED);
        organizations.save(orgC);
        assertTrue(shares.resolveEffectiveScopes(orgC.getId(), orgB.getId()).isEmpty());
    }

    @Test
    void revocationTakesEffectImmediately() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        var revoked = delete(policy(orgB.getId(), ShareScope.CARE));
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
        assertTrue(mapper.readTree(get(policies(orgB.getId())).body()).isEmpty());

        // Revoking a non-existent policy is an explicit 404, never a silent success.
        assertEquals(404, delete(policy(orgB.getId(), ShareScope.CARE)).statusCode());
    }

    @Test
    void hidingOwnerInvalidatesGrantsAndNeverSilentlyReactivates() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // Hiding the owner kills the grant immediately.
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":false}").statusCode());
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
        assertTrue(mapper.readTree(get(policies(orgB.getId())).body()).isEmpty());

        // Re-discovery must not silently restore the deleted grant.
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":true}").statusCode());
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));

        // The invalidation is audited with actor, owner, target, scope and action.
        assertTrue(auditCount("owner_org_id = '" + orgB.getId() + "' AND recipient_org_id = '"
                + orgA.getId() + "' AND scope = 'CARE' AND action = 'INVALIDATE'") >= 1);
        assertTrue(auditCount("owner_org_id = '" + orgB.getId() + "' AND scope = 'CARE' AND action = 'GRANT'") >= 1);
    }

    @Test
    void hidingRecipientRemovesItFromEveryAllowlist() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        discover(orgB.getId(), "admin-b@example.org");
        login("admin-a@example.org");
        put(policy(orgA.getId(), ShareScope.CARE), allowlist(orgA.getId(), orgB.getId()));
        assertEquals(Set.of(ShareScope.CARE), shares.resolveEffectiveScopes(orgB.getId(), orgA.getId()));

        // The recipient hides itself: removed from A's allowlist with audit.
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        assertEquals(200, patch("/api/v1/organizations/" + orgB.getId() + "/visibility",
                "{\"discoverable\":false}").statusCode());

        login("admin-a@example.org");
        JsonNode list = mapper.readTree(get(policies(orgA.getId())).body());
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).get("recipients").size(), "hidden recipient must be dropped");
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgB.getId(), orgA.getId()));

        // Becoming discoverable again does not re-add the recipient.
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgB.getId(), orgA.getId()));

        assertTrue(auditCount("owner_org_id = '" + orgA.getId() + "' AND recipient_org_id = '"
                + orgB.getId() + "' AND action = 'INVALIDATE'") >= 1);
    }

    @Test
    void hiddenOwnerIsNotLeakedThroughPolicyOrDirectoryQueries() throws Exception {
        seed();
        // A hidden owner looks like an organization without grants: empty
        // results, never an error that distinguishes it.
        assertEquals(Set.of(), shares.resolveEffectiveScopes(orgA.getId(), orgB.getId()));
        login("admin-a@example.org");
        assertFalse(directoryContains(directory(), orgB.getId()));

        // The hidden owner can still administer its own (ineffective) grants.
        discover(orgA.getId(), "admin-a@example.org");
        login("admin-b@example.org");
        assertEquals(200, put(policy(orgB.getId(), ShareScope.CARE),
                allowlist(orgB.getId(), orgA.getId())).statusCode());
        var list = get(policies(orgB.getId()));
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(1, mapper.readTree(list.body()).size());

        // Outsiders cannot enumerate another organization's policies.
        login("admin-a@example.org");
        assertEquals(403, get(policies(orgB.getId())).statusCode());
    }

    @Test
    void auditLedgerRecordsGrantsRevocationsAndInvalidations() throws Exception {
        seed();
        discover(orgA.getId(), "admin-a@example.org");
        discover(orgC.getId(), "admin-c@example.org");
        login("admin-b@example.org");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":true}");
        put(policy(orgB.getId(), ShareScope.CARE), allowlist(orgB.getId(), orgA.getId()));
        put(policy(orgB.getId(), ShareScope.CARE),
                "{\"audience\":\"ALLOWLIST\",\"recipientIds\":[\"" + orgA.getId() + "\",\"" + orgC.getId() + "\"]}");
        delete(policy(orgB.getId(), ShareScope.CARE));
        put(policy(orgB.getId(), ShareScope.VISITS), "{\"audience\":\"ALL_DISCOVERABLE\"}");
        patch("/api/v1/organizations/" + orgB.getId() + "/visibility", "{\"discoverable\":false}");

        // Actor, owner, target, scope and action are recorded for every step.
        String actor = adminB.getId().toString();
        assertEquals(1, auditCount("actor_user_id = '" + actor + "' AND owner_org_id = '" + orgB.getId()
                + "' AND recipient_org_id IS NULL AND scope = 'CARE' AND action = 'GRANT'"));
        assertEquals(1, auditCount("actor_user_id = '" + actor + "' AND owner_org_id = '" + orgB.getId()
                + "' AND recipient_org_id = '" + orgA.getId() + "' AND scope = 'CARE' AND action = 'GRANT'"));
        assertEquals(1, auditCount("actor_user_id = '" + actor + "' AND owner_org_id = '" + orgB.getId()
                + "' AND recipient_org_id = '" + orgC.getId() + "' AND scope = 'CARE' AND action = 'GRANT'"));
        assertEquals(2, auditCount("owner_org_id = '" + orgB.getId()
                + "' AND scope = 'CARE' AND action = 'REVOKE'"));
        assertEquals(1, auditCount("owner_org_id = '" + orgB.getId()
                + "' AND scope = 'VISITS' AND action = 'GRANT'"));
        assertEquals(1, auditCount("owner_org_id = '" + orgB.getId()
                + "' AND scope = 'VISITS' AND action = 'INVALIDATE'"));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM organization_share_audit", Integer.class));
    }

    @Test
    void csrfProtectionAppliesToSharingEndpoints() throws Exception {
        seed();
        login("admin-b@example.org");
        // State-changing requests without a CSRF token are never processed.
        assertEquals(403, client.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/organizations/" + orgB.getId() + "/visibility")))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"discoverable\":true}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(
                        URI.create(base(policies(orgB.getId()) + "/CARE")))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"audience\":\"PRIVATE\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(orgB.isDiscoverable());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM organization_share_policies", Integer.class));
    }
}
