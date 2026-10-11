package org.miezmerker.backend.bootstrap;

import java.util.Arrays;
import javax.sql.DataSource;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Trusted local operator command, with no HTTP listener or normal bootstrap runner. */
public final class DevDemoCommand {
    private DevDemoCommand() {}

    public static void requireDevelopment(String profiles, String optIn, String url) {
        if (!"dev".equals(profiles) || !"true".equals(optIn)
                || url == null || !url.matches(
                    "jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1\\]):[0-9]{1,5}/[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Demo setup requires exactly the dev profile, "
                    + "MIEZMERKER_DEMO_ENABLED=true and an explicit loopback PostgreSQL DB_URL (no URL options).");
        }
    }

    public static void run(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: dev-demo (configuration via environment only)");
        var env = System.getenv();
        requireDevelopment(env.get("SPRING_PROFILES_ACTIVE"), env.get("MIEZMERKER_DEMO_ENABLED"), env.get("DB_URL"));
        for (String key : new String[] {"DB_USER", "DB_PASSWORD", "MIEZMERKER_DEMO_PASSWORD"}) {
            if (env.get(key) == null || env.get(key).isBlank()) {
                throw new IllegalArgumentException(key + " must be explicitly supplied");
            }
        }
        var app = new SpringApplication(DemoConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> {
            var settings = new java.util.HashMap<String, Object>();
            for (String prefix : new String[] {"spring.datasource.", "spring.flyway."}) {
                settings.put(prefix + "url", env.get("DB_URL"));
                settings.put(prefix + (prefix.contains("flyway") ? "user" : "username"), env.get("DB_USER"));
                settings.put(prefix + "password", env.get("DB_PASSWORD"));
            }
            settings.put("spring.flyway.enabled", true);
            settings.put("spring.flyway.locations", "classpath:db/migration");
            settings.put("spring.flyway.default-schema", "public");
            settings.put("spring.flyway.schemas", "public");
            settings.put("spring.jpa.hibernate.ddl-auto", "validate");
            settings.put("spring.jpa.properties.hibernate.default_schema", "public");
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("verified-demo-target", settings));
            if (!Arrays.equals(new String[] {"dev"}, context.getEnvironment().getActiveProfiles())) {
                throw new IllegalArgumentException("Demo setup rejects additional Spring profiles");
            }
        });
        // Pin the verified target; external Spring datasource overrides cannot redirect writes.
        try (var context = app.run("--spring.profiles.active=dev", "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + env.get("DB_URL"))) {
            var seed = context.getBean(DemoSeedService.class);
            var userId = seed.seed(env.get("MIEZMERKER_DEMO_PASSWORD"));
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                SystemRoleMaintenance.apply(connection, DemoSeedService.grantOperation(userId), userId,
                        "GRANT", "local-dev-demo", DemoSeedService.GRANT_REASON);
            }
            seed.verifyPrivilege(userId);
            System.out.println("Demo setup verified: " + DemoFixtures.SLUG + ", five cats, one feeding site, ACTIVE ADMIN and SYSADMIN.");
        }
    }

    // Deliberately no @Configuration/@Component: normal application scanning must not import this.
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Organization.class)
    @EnableJpaRepositories(basePackageClasses = OrganizationRepository.class)
    @Import({DemoSeedService.class, org.miezmerker.backend.security.SystemAuthorizationService.class})
    public static class DemoConfiguration {
        @Bean DataSource demoDataSource(org.springframework.core.env.Environment env) {
            // Construct directly: JNDI, datasource type and other pool overrides cannot redirect this command.
            var dataSource = new com.zaxxer.hikari.HikariDataSource();
            dataSource.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
            dataSource.setUsername(env.getRequiredProperty("spring.datasource.username"));
            dataSource.setPassword(env.getRequiredProperty("spring.datasource.password"));
            dataSource.setConnectionInitSql("SET search_path TO public");
            return dataSource;
        }
        @Bean PasswordEncoder demoPasswordEncoder() { return new BCryptPasswordEncoder(12); }
    }
}
