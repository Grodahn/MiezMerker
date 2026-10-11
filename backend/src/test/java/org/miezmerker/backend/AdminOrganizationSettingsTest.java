package org.miezmerker.backend;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.admin.OrganizationSettingsService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP tests cover the rendered frontend, cookies, CSRF, session context and persistence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("admin")
@Tag("auth")
class AdminOrganizationSettingsTest {
    static final String PATH = "/admin/organization/settings";
    @Value("${local.server.port}") int port;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired SharePolicyService shares;
    @Autowired OrganizationSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    HttpClient client;
    Organization a, b, hidden, inactive;
    AppUser admin;
    OrganizationMembership membership;

    @BeforeEach void seed() {
        cleaner.clean();
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        a = organizations.save(new Organization("owner", "Owner", null));
        b = organizations.save(new Organization("recipient", "Visible recipient", null));
        b.setDiscoverable(true); organizations.save(b);
        hidden = organizations.save(new Organization("secret", "SECRET ORGANIZATION", null));
        inactive = organizations.save(new Organization("inactive", "INACTIVE ORGANIZATION", null));
        inactive.setDiscoverable(true); inactive.setStatus(OrganizationStatus.DISABLED); organizations.save(inactive);
        admin = users.save(new AppUser("admin@example.org", passwords.encode("password-settings")));
        membership = memberships.save(new OrganizationMembership(a, admin, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    String field(String html, String name) {
        var matcher = Pattern.compile("name=\"" + name + "\"[^>]*value=\"([^\"]*)\"").matcher(html);
        assertTrue(matcher.find(), "missing field " + name);
        return matcher.group(1);
    }
    HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    String enc(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    void login() throws Exception {
        var page = get("/admin/login");
        assertEquals(302, post("/admin/login", "email=admin%40example.org&password=password-settings&_csrf="
                + enc(field(page.body(), "_csrf"))).statusCode());
    }
    String form(String html) {
        return "_csrf=" + enc(field(html, "_csrf")) + "&formOrganizationId=" + field(html, "formOrganizationId")
                + "&version=" + field(html, "version");
    }
    String choices(String care, String visits, String site) {
        return "&CARE=" + care + "&VISITS=" + visits + "&SITE_LABEL=" + site;
    }
    void save(String extra) throws Exception {
        var page = get(PATH); assertEquals(200, page.statusCode(), page.body());
        assertEquals(302, post(PATH, form(page.body()) + extra).statusCode());
    }
    Set<ShareScope> effective() { return shares.resolveEffectiveScopes(b.getId(), a.getId()); }
    void policy(ShareScope scope, ShareAudience audience) {
        shares.upsert(admin.getId(), a.getId(), scope, audience,
                audience == ShareAudience.ALLOWLIST ? List.of(b.getId()) : List.of());
    }

    @Test void defaultHiddenPrivateAndEligibleDirectory() throws Exception {
        login();
        var page = get(PATH);
        assertEquals(200, page.statusCode(), page.body());
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(Pattern.compile("id=\"hidden\"[^>]*checked").matcher(page.body()).find());
        assertTrue(page.body().contains("Visible recipient"));
        for (var excluded : List.of(hidden, inactive)) {
            assertFalse(page.body().contains(excluded.getDisplayName()));
            assertFalse(page.body().contains(excluded.getId().toString()));
        }
        assertTrue(settings.read(admin.getId(), a.getId()).choices().values().stream()
                .allMatch(c -> c.audience() == ShareAudience.PRIVATE));
    }
    @Test void visibilityAloneSharesNothingAndExpansionsRequireConfirmation() throws Exception {
        login();
        save(choices("PRIVATE", "PRIVATE", "PRIVATE"));
        assertFalse(organizations.findById(a.getId()).orElseThrow().isDiscoverable());
        assertTrue(get(PATH).body().contains("ausdrücklich bestätigen"));
        save(choices("PRIVATE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertTrue(organizations.findById(a.getId()).orElseThrow().isDiscoverable());
        assertTrue(effective().isEmpty());
        assertTrue(get(PATH).body().contains("Einstellungen gespeichert"));
    }
    @Test void emptyRecipientStateAndAccessibleLabels() throws Exception {
        b.setDiscoverable(false); organizations.save(b);
        login();
        String html = get(PATH).body();
        assertTrue(html.contains("Keine geeigneten Empfänger verfügbar"));
        assertTrue(html.contains("<legend>Betreuungs- und Profilinformationen</legend>"));
        assertTrue(html.contains("aria-describedby=\"visibility-help\""));
        assertTrue(html.contains("role=\"status\""));
        assertFalse(html.contains(b.getId().toString()));
    }
    @Test void audienceTransitionsScopesAndImmediateRevocation() throws Exception {
        login();
        save(choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertEquals(Set.of(ShareScope.CARE), effective());
        save(choices("ALLOWLIST", "ALLOWLIST", "ALLOWLIST")
                + "&CARERecipients=" + b.getId() + "&VISITSRecipients=" + b.getId()
                + "&SITE_LABELRecipients=" + b.getId() + "&confirmed=true");
        assertEquals(Set.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL), effective());
        assertFalse(effective().contains(ShareScope.PHOTO));
        save(choices("PRIVATE", "PRIVATE", "PRIVATE"));
        assertTrue(effective().isEmpty());
        save(choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertEquals(Set.of(ShareScope.CARE), effective(), "revoked visits/site must not reactivate");
    }
    @Test void dependenciesAndInvalidRecipientsFailAtomicallyWithoutLeaks() throws Exception {
        login();
        save(choices("PRIVATE", "ALL_DISCOVERABLE", "PRIVATE") + "&confirmed=true");
        assertTrue(get(PATH).body().contains("Besuche benötigen"));
        assertFalse(organizations.findById(a.getId()).orElseThrow().isDiscoverable());
        for (UUID recipient : List.of(hidden.getId(), inactive.getId(), a.getId(), UUID.randomUUID())) {
            save(choices("ALLOWLIST", "PRIVATE", "PRIVATE") + "&CARERecipients=" + recipient + "&confirmed=true");
            String html = get(PATH).body();
            assertTrue(html.contains("gültige Freigabe"));
            assertFalse(html.contains(hidden.getDisplayName()));
            assertFalse(html.contains(hidden.getId().toString()));
            assertTrue(effective().isEmpty());
        }
    }
    @Test void hideThenShowNeverRestoresGrants() throws Exception {
        login();
        save(choices("ALL_DISCOVERABLE", "ALL_DISCOVERABLE", "ALL_DISCOVERABLE") + "&confirmed=true");
        save("&hidden=true");
        assertTrue(effective().isEmpty());
        assertTrue(shares.listOutgoing(admin.getId(), a.getId()).isEmpty());
        save(choices("PRIVATE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertTrue(effective().isEmpty());
    }
    @Test void staleFormCannotRestoreRemovedRecipient() throws Exception {
        login();
        save(choices("ALLOWLIST", "PRIVATE", "PRIVATE") + "&CARERecipients=" + b.getId() + "&confirmed=true");
        String old = get(PATH).body();
        AppUser adminB = users.save(new AppUser("b@example.org", passwords.encode("password-settings")));
        memberships.save(new OrganizationMembership(b, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        shares.setDiscoverable(adminB.getId(), b.getId(), false);
        assertEquals(302, post(PATH, form(old) + choices("ALLOWLIST", "PRIVATE", "PRIVATE")
                + "&CARERecipients=" + b.getId() + "&confirmed=true").statusCode());
        String fresh = get(PATH).body();
        assertTrue(fresh.contains("haben sich geändert"));
        assertFalse(fresh.contains(b.getId().toString()));
        assertFalse(fresh.contains(b.getDisplayName()));
        shares.setDiscoverable(adminB.getId(), b.getId(), true);
        assertTrue(effective().isEmpty());
    }
    @Test void hiddenAndInactiveLegacyRecipientsAreFilteredFromPolicyViews() {
        a.setDiscoverable(true); organizations.save(a);
        policy(ShareScope.CARE, ShareAudience.ALLOWLIST);
        b.setDiscoverable(false); organizations.save(b);
        assertTrue(shares.listOutgoing(admin.getId(), a.getId()).getFirst().recipients().isEmpty());
        b.setDiscoverable(true); b.setStatus(OrganizationStatus.DISABLED); organizations.save(b);
        assertTrue(shares.listOutgoing(admin.getId(), a.getId()).getFirst().recipients().isEmpty());
    }
    @Test void equallyNamedPolicyRecipientsHaveStableOrderForFormVersions() {
        Organization other = organizations.save(new Organization("same-name", b.getDisplayName(), null));
        other.setDiscoverable(true); organizations.save(other);
        List<UUID> expected = java.util.stream.Stream.of(b.getId(), other.getId()).sorted().toList();
        var policy = shares.upsert(admin.getId(), a.getId(), ShareScope.CARE, ShareAudience.ALLOWLIST,
                List.of(expected.get(1), expected.get(0)));
        assertEquals(expected, policy.recipients().stream().map(SharePolicyService.RecipientView::id).toList(),
                "equal names must not leave version fingerprints dependent on database/insertion order");
    }
    @Test void concurrentRecipientHideCannotBeUndoneByPendingSave() throws Exception {
        AppUser adminB = users.save(new AppUser("b@example.org", passwords.encode("password-settings")));
        memberships.save(new OrganizationMembership(b, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        var snapshot = settings.read(admin.getId(), a.getId());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var hiding = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        shares.setDiscoverable(adminB.getId(), b.getId(), false);
                        locked.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                        } catch (InterruptedException e) { throw new RuntimeException(e); }
                    }));
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var saving = executor.submit(() -> {
                var requested = new EnumMap<ShareScope, OrganizationSettingsService.Choice>(ShareScope.class);
                requested.put(ShareScope.CARE, new OrganizationSettingsService.Choice(ShareAudience.ALLOWLIST, List.of(b.getId())));
                requested.put(ShareScope.VISITS, new OrganizationSettingsService.Choice(ShareAudience.PRIVATE, List.of()));
                requested.put(ShareScope.SITE_LABEL, new OrganizationSettingsService.Choice(ShareAudience.PRIVATE, List.of()));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> settings.save(admin.getId(), a.getId(), snapshot.version(), false, requested, true, false));
            });
            // The recipient lock holds the pending mutation until hiding commits.
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> saving.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            hiding.get(10, java.util.concurrent.TimeUnit.SECONDS);
            saving.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(organizations.findById(a.getId()).orElseThrow().isDiscoverable(), "whole form must roll back");
            assertTrue(shares.listOutgoing(admin.getId(), a.getId()).isEmpty());
            shares.setDiscoverable(adminB.getId(), b.getId(), true);
            assertTrue(effective().isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
    @Test void photoCannotBeEnabledAndExistingPolicyCanBeRevoked() throws Exception {
        a.setDiscoverable(true); organizations.save(a);
        policy(ShareScope.CARE, ShareAudience.ALL_DISCOVERABLE);
        policy(ShareScope.PHOTO, ShareAudience.ALL_DISCOVERABLE);
        login();
        assertTrue(get(PATH).body().contains("Bestehende Foto-Policy widerrufen"));
        save(choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&revokePhoto=true&PHOTO=ALL_DISCOVERABLE");
        assertEquals(Set.of(ShareScope.CARE), effective());
        policy(ShareScope.PHOTO, ShareAudience.ALL_DISCOVERABLE);
        save(choices("PRIVATE", "PRIVATE", "PRIVATE"));
        // A dormant PHOTO grant created through the existing policy API must
        // not become effective merely by enabling CARE in this UI.
        policy(ShareScope.PHOTO, ShareAudience.ALL_DISCOVERABLE);
        save(choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertEquals(Set.of(ShareScope.CARE), effective());
        // Nor may broadening CARE broaden an existing PHOTO audience.
        save(choices("ALLOWLIST", "PRIVATE", "PRIVATE") + "&CARERecipients=" + b.getId());
        policy(ShareScope.PHOTO, ShareAudience.ALL_DISCOVERABLE);
        save(choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true");
        assertEquals(Set.of(ShareScope.CARE), effective());
    }
    @Test void csrfIsRequiredAndForgedOrgCannotModifyAnotherTenant() throws Exception {
        login();
        assertEquals(403, post(PATH, "formOrganizationId=" + a.getId() + "&version=x&hidden=true").statusCode());
        String page = get(PATH).body();
        assertEquals(302, post(PATH, form(page).replace(a.getId().toString(), b.getId().toString())
                + choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true").statusCode());
        assertFalse(organizations.findById(a.getId()).orElseThrow().isDiscoverable());
        assertTrue(shares.listOutgoing(admin.getId(), a.getId()).isEmpty());
    }
    @Test void switchingOrganizationsRejectsOldFormEvenWithBothAdminMemberships() throws Exception {
        memberships.save(new OrganizationMembership(b, admin, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        login();
        String orgPage = get("/admin/org").body();
        assertEquals(302, post("/admin/org", "_csrf=" + enc(field(orgPage, "_csrf")) + "&organizationId=" + a.getId()).statusCode());
        String old = get(PATH).body();
        assertEquals(302, post("/admin/org", "_csrf=" + enc(field(old, "_csrf")) + "&organizationId=" + b.getId()).statusCode());
        assertEquals(302, post(PATH, form(old) + choices("ALL_DISCOVERABLE", "PRIVATE", "PRIVATE") + "&confirmed=true").statusCode());
        String fresh = get(PATH).body();
        assertEquals(b.getId().toString(), field(fresh, "formOrganizationId"));
        assertTrue(fresh.contains("Organisation wurde gewechselt"));
        assertTrue(shares.listOutgoing(admin.getId(), b.getId()).isEmpty());
        assertFalse(organizations.findById(a.getId()).orElseThrow().isDiscoverable());
    }
    @Test void memberDisabledMembershipAndDisabledAccountAreDenied() throws Exception {
        login();
        for (String update : List.of("role='MEMBER'", "role='ADMIN', status='DISABLED'", "status='PENDING'")) {
            jdbc.update("update organization_memberships set " + update + " where id=?", membership.getId());
            assertEquals(403, get(PATH).statusCode());
            assertEquals(403, post(PATH, "_csrf=" + enc(apiCsrf()) + "&formOrganizationId=" + a.getId()
                    + "&version=x&hidden=true").statusCode());
        }
        jdbc.update("update organization_memberships set status='ACTIVE' where id=?", membership.getId());
        admin.setStatus(UserStatus.DISABLED); users.save(admin);
        assertEquals(403, get(PATH).statusCode());
    }
    String apiCsrf() throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
    }
    @Test void sysadminWithoutMembershipHasNoImplicitPermission() throws Exception {
        // Existing global role model, no new authority framework.
        jdbc.update("insert into app_user_system_roles(user_id, role, enabled) values (?, 'SYSADMIN', TRUE)", admin.getId());
        memberships.deleteAll();
        login();
        assertEquals(403, get(PATH).statusCode());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> settings.read(admin.getId(), a.getId()));
    }
}
