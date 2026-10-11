package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.miezmerker.backend.bootstrap.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.security.SystemAuthorizationService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(DemoSeedService.class)
@Tag("auth")
@Tag("system")
@Tag("schema")
class DevDemoTest {
    // Independent test credential; the agreed local demo password is injected, never committed.
    static final String PASSWORD = "fixture-test-password";
    @Autowired DemoSeedService seed;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired CatRepository cats;
    @Autowired FeedingSiteRepository sites;
    @Autowired PasswordEncoder passwords;
    @Autowired SystemAuthorizationService system;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;

    @BeforeEach void clean() {
        cleaner.clean();
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }
    UUID setup() throws Exception {
        UUID user = seed.seed(PASSWORD);
        try (var connection = dataSource.getConnection()) {
            SystemRoleMaintenance.apply(connection, DemoSeedService.grantOperation(user), user,
                    "GRANT", "local-dev-demo", DemoSeedService.GRANT_REASON);
        }
        seed.verifyPrivilege(user);
        return user;
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    @Test void freshSetupAuthenticatesAndUsesNormalTenantApis() throws Exception {
        var userId = setup();
        var org = organizations.findBySlug(DemoFixtures.SLUG).orElseThrow();
        var user = users.findById(userId).orElseThrow();
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(DemoFixtures.ORG_NAME, org.getDisplayName());
        assertFalse(org.isDiscoverable());
        assertTrue(passwords.matches(PASSWORD, user.getPasswordHash()));
        assertNotEquals(PASSWORD, user.getPasswordHash());
        var member = memberships.findByOrganizationIdAndUserId(org.getId(), userId).orElseThrow();
        assertEquals(MembershipStatus.ACTIVE, member.getStatus());
        assertEquals(MembershipRole.ADMIN, member.getRole());
        assertTrue(system.isSysadmin(userId));
        var profiles = cats.findByOrganizationId(org.getId());
        assertEquals(5, profiles.size());
        assertEquals(Set.of("1", "2", "3", "4", "5"), new HashSet<>(profiles.stream().map(Cat::getChipId).toList()));
        assertEquals(5, profiles.stream().map(Cat::getName).distinct().count());
        for (var cat : profiles) {
            assertNotNull(cat.getId());
            assertFalse(cat.getNotes().isBlank());
            assertEquals(org.getId(), cat.getOrganization().getId());
            var fixture = DemoFixtures.CATS.stream().filter(f -> f.chipId().equals(cat.getChipId())).findFirst().orElseThrow();
            assertEquals(fixture.name(), cat.getName());
            assertEquals(fixture.description(), cat.getNotes());
        }
        var places = sites.findByOrganizationId(org.getId());
        assertEquals(1, places.size());
        assertEquals(DemoFixtures.SITE_NAME, places.getFirst().getName());
        assertEquals(DemoFixtures.SITE_DESCRIPTION, places.getFirst().getDescription());
        assertEquals(org.getId(), places.getFirst().getOrganization().getId());
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM demo_fixture_records WHERE record_key<>'lock'", Integer.class));
        var token = mapper.readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
        var login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", DemoFixtures.EMAIL, "password", PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        assertTrue(get("/api/v1/organizations").body().contains(org.getId().toString()));
        assertEquals(5, mapper.readTree(get("/api/v1/organizations/" + org.getId() + "/cats").body()).size());
        assertEquals(1, mapper.readTree(get("/api/v1/organizations/" + org.getId() + "/feeding-sites").body()).size());
        var foreign = organizations.save(new Organization("foreign", "Foreign", null));
        for (String resource : List.of("cats", "feeding-sites", "visits")) {
            assertEquals(403, get("/api/v1/organizations/" + foreign.getId() + "/" + resource).statusCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nodes", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM raw_observations", Integer.class));
    }
    @Test void existingDatabaseAndRepeatPreserveIdsAndEditedSite() throws Exception {
        var other = organizations.save(new Organization("other", "Other", null));
        var existing = users.save(new AppUser("real@example.org", passwords.encode(PASSWORD)));
        var realCat = cats.save(new Cat(other, "1", "Real cat", null, "Real notes"));
        var user = setup();
        var org = organizations.findBySlug(DemoFixtures.SLUG).orElseThrow();
        var site = sites.findByOrganizationId(org.getId()).getFirst();
        site.setDescription("Vom lokalen Betreiber bearbeitet");
        sites.save(site);
        var before = jdbc.queryForList("SELECT * FROM demo_fixture_records ORDER BY record_key");
        var hash = users.findById(user).orElseThrow().getPasswordHash();
        assertEquals(user, setup());
        assertEquals(before, jdbc.queryForList("SELECT * FROM demo_fixture_records ORDER BY record_key"));
        assertEquals(hash, users.findById(user).orElseThrow().getPasswordHash());
        assertEquals(2, users.count());
        assertEquals(2, organizations.count());
        assertEquals(6, cats.count());
        assertEquals(1, sites.count());
        assertEquals("Vom lokalen Betreiber bearbeitet", sites.findById(site.getId()).orElseThrow().getDescription());
        assertEquals("Real cat", cats.findById(realCat.getId()).orElseThrow().getName());
        assertFalse(system.isSysadmin(existing.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_role_audit", Integer.class));
    }
    @Test void unownedEmailAndSlugAreNeverAdopted() {
        var account = users.save(new AppUser(DemoFixtures.EMAIL, passwords.encode(PASSWORD)));
        assertThrows(IllegalStateException.class, () -> seed.seed(PASSWORD));
        assertEquals(0, organizations.count());
        assertFalse(system.isSysadmin(account.getId()));
        cleaner.clean();
        organizations.save(new Organization(DemoFixtures.SLUG, "Real organization", null));
        assertThrows(IllegalStateException.class, () -> seed.seed(PASSWORD));
        assertEquals(0, users.count());
        assertEquals(0, cats.count());
        assertEquals(0, sites.count());
    }
    @Test void conflictingChipAndIncompleteManifestFailWithoutRepair() throws Exception {
        setup();
        var org = organizations.findBySlug(DemoFixtures.SLUG).orElseThrow();
        var owned = cats.findByOrganizationIdAndChipId(org.getId(), "1").orElseThrow();
        cats.delete(owned);
        var replacement = cats.save(new Cat(org, "1", "Unowned replacement", null, "Preserve me"));
        assertThrows(IllegalStateException.class, () -> seed.seed(PASSWORD));
        assertEquals("Unowned replacement", cats.findById(replacement.getId()).orElseThrow().getName());
        jdbc.update("DELETE FROM demo_fixture_records WHERE record_key='cat-2'");
        assertThrows(IllegalStateException.class, () -> seed.seed(PASSWORD));
        assertEquals(5, cats.count());
    }
    @Test void revokedPrivilegeDisabledMembershipAndChangedPasswordAreNotRestored() throws Exception {
        var user = setup();
        try (var connection = dataSource.getConnection()) {
            SystemRoleMaintenance.apply(connection, UUID.randomUUID(), user, "REVOKE", "test-operator", "Revoke demo access");
        }
        assertThrows(IllegalStateException.class, this::setup);
        assertFalse(system.isSysadmin(user));
        assertThrows(IllegalStateException.class, () -> seed.seed("another-test-password"));
        var org = organizations.findBySlug(DemoFixtures.SLUG).orElseThrow();
        var member = memberships.findByOrganizationIdAndUserId(org.getId(), user).orElseThrow();
        member.disable();
        memberships.save(member);
        assertThrows(IllegalStateException.class, () -> seed.seed(PASSWORD));
        assertEquals(MembershipStatus.DISABLED, memberships.findById(member.getId()).orElseThrow().getStatus());
    }
    @Test void productionUnknownProfilesRemoteAndAmbiguousTargetsAreRejected() {
        for (String profile : new String[] {"prod", "production", "dev,prod", "dev,test", "", "test"}) {
            assertThrows(IllegalArgumentException.class, () -> DevDemoCommand.requireDevelopment(profile, "true", "jdbc:postgresql://localhost:5432/demo"));
        }
        assertThrows(IllegalArgumentException.class, () -> DevDemoCommand.requireDevelopment(null, "true", null));
        assertThrows(IllegalArgumentException.class, () -> DevDemoCommand.requireDevelopment("dev", "false", "jdbc:postgresql://localhost:5432/demo"));
        for (String url : List.of("jdbc:postgresql://production:5432/demo", "jdbc:postgresql://localhost/demo", "jdbc:postgresql://localhost:5432/demo?host=production")) {
            assertThrows(IllegalArgumentException.class, () -> DevDemoCommand.requireDevelopment("dev", "true", url));
        }
        DevDemoCommand.requireDevelopment("dev", "true", "jdbc:postgresql://127.0.0.1:5432/demo");
        assertEquals(0, users.count());
        assertEquals(0, cats.count());
    }
}
