package org.miezmerker.backend.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.security.SystemAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/** Only imported by the explicit operator command (and tests), never component-scanned. */
public class DemoSeedService {
    public static final String GRANT_REASON = "DEMO ONLY issue #111 initial local setup; release cleanup gate #113";
    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final CatRepository cats;
    private final FeedingSiteRepository sites;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final SystemAuthorizationService system;

    public DemoSeedService(AppUserRepository users, OrganizationRepository organizations,
            MembershipRepository memberships, CatRepository cats, FeedingSiteRepository sites,
            PasswordEncoder passwords, JdbcTemplate jdbc, SystemAuthorizationService system) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.cats = cats;
        this.sites = sites;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.system = system;
    }

    public static UUID grantOperation(UUID userId) {
        return UUID.nameUUIDFromBytes((DemoFixtures.VERSION + ":sysadmin:" + userId)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public UUID seed(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("MIEZMERKER_DEMO_PASSWORD requires 12 characters and at most 72 UTF-8 bytes");
        }
        var lock = jdbc.queryForList("SELECT record_key FROM demo_fixture_records WHERE fixture_version=? AND record_key='lock' FOR UPDATE",
                DemoFixtures.VERSION);
        require(lock.size() == 1, "Missing demo manifest lock; apply migrations first");
        Map<String, UUID> manifest = new HashMap<>();
        jdbc.query("SELECT record_key, record_id FROM demo_fixture_records WHERE fixture_version=? AND record_key<>'lock'",
                rows -> { manifest.put(rows.getString(1), rows.getObject(2, UUID.class)); }, DemoFixtures.VERSION);
        if (manifest.isEmpty()) {
            require(users.findByEmail(DemoFixtures.EMAIL).isEmpty(), "Demo email belongs to an unowned account; refusing promotion or password change");
            require(organizations.findBySlug(DemoFixtures.SLUG).isEmpty(), "Demo slug belongs to an unowned organization; refusing adoption");
            var org = organizations.saveAndFlush(new Organization(DemoFixtures.SLUG, DemoFixtures.ORG_NAME, null));
            var user = users.saveAndFlush(new AppUser(DemoFixtures.EMAIL, passwords.encode(password), "Demo Administration"));
            remember("organization", org.getId());
            remember("user", user.getId());
            remember("membership", memberships.saveAndFlush(new OrganizationMembership(org, user,
                    MembershipRole.ADMIN, MembershipStatus.ACTIVE)).getId());
            remember("feeding-site", sites.saveAndFlush(new FeedingSite(org, DemoFixtures.SITE_NAME,
                    DemoFixtures.SITE_DESCRIPTION, null, null, null)).getId());
            for (var fixture : DemoFixtures.CATS) {
                remember("cat-" + fixture.chipId(), cats.saveAndFlush(new Cat(org, fixture.chipId(),
                        fixture.name(), "ACTIVE", fixture.description())).getId());
            }
            return user.getId();
        }
        require(manifest.size() == 9, "Incomplete or unknown demo manifest; refusing repair");
        var org = organizations.findById(id(manifest, "organization")).orElseThrow(() -> conflict("Missing owned demo organization"));
        var user = users.findById(id(manifest, "user")).orElseThrow(() -> conflict("Missing owned demo account"));
        require(DemoFixtures.SLUG.equals(org.getSlug()) && org.getStatus() == OrganizationStatus.ACTIVE,
                "Demo organization slug/status changed; refusing overwrite");
        require(DemoFixtures.EMAIL.equals(user.getEmail()) && user.getStatus() == UserStatus.ACTIVE
                && passwords.matches(password, user.getPasswordHash()), "Demo account changed or supplied password differs; refusing reset/reactivation");
        var member = memberships.findByOrganizationIdAndUserId(org.getId(), user.getId()).orElseThrow(() -> conflict("Missing demo membership"));
        require(member.getId().equals(id(manifest, "membership")) && member.getRole() == MembershipRole.ADMIN
                && member.getStatus() == MembershipStatus.ACTIVE, "Demo membership changed; refusing escalation");
        require(sites.findByOrganizationId(org.getId()).size() == 1 && sites.findByIdAndOrganizationId(
                id(manifest, "feeding-site"), org.getId()).isPresent(), "Demo feeding-site ownership/count changed; refusing repair");
        require(cats.findByOrganizationId(org.getId()).size() == 5, "Demo cat count changed; refusing repair");
        for (var fixture : DemoFixtures.CATS) {
            var cat = cats.findByIdAndOrganizationId(id(manifest, "cat-" + fixture.chipId()), org.getId())
                    .orElseThrow(() -> conflict("Missing or foreign owned demo cat"));
            require(fixture.chipId().equals(cat.getChipId()), "Demo chip changed; refusing remapping");
        }
        return user.getId(); // Preserve all names, descriptions, coordinates and password hashes.
    }

    public void verifyPrivilege(UUID userId) {
        require(system.isSysadmin(userId), "Demo account is inactive or SYSADMIN is missing/revoked; "
                + "replay does not restore authority. Follow docs/sysadmin.md");
    }

    private void remember(String key, UUID id) {
        jdbc.update("INSERT INTO demo_fixture_records(fixture_version,record_key,record_id) VALUES (?,?,?)",
                DemoFixtures.VERSION, key, id);
    }
    private static UUID id(Map<String, UUID> manifest, String key) {
        var id = manifest.get(key);
        require(id != null, "Missing demo manifest entry: " + key);
        return id;
    }
    private static IllegalStateException conflict(String message) { return new IllegalStateException(message); }
    private static void require(boolean condition, String message) { if (!condition) throw conflict(message); }
}
