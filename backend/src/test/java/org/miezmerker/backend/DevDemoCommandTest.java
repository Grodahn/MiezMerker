package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.DevDemoCommand;
import org.miezmerker.backend.bootstrap.DemoSeedService;
import org.miezmerker.backend.bootstrap.SystemRoleMaintenance;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Tag("auth")
@Tag("system")
@Tag("schema")
class DevDemoCommandTest {
    @Test void standaloneContextHasNoWebServerBootstrapOrAutomaticSeedAndSurvivesRestart() throws Exception {
        var app = new SpringApplication(DevDemoCommand.DemoConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        String[] args = {"--spring.datasource.url=jdbc:h2:mem:demo-command;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.profiles.active=dev"};
        UUID user;
        try (var context = app.run(args)) {
            assertFalse(context.containsBean("bootstrapRunner"));
            assertFalse(context.containsBean("security"));
            assertNotNull(context.getBean(DemoSeedService.class));
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_roles", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cats", Integer.class));
            user = context.getBean(DemoSeedService.class).seed(DevDemoTest.PASSWORD);
            assertTrue(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, user).startsWith("$2a$12$"));
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertTrue(SystemRoleMaintenance.apply(connection, DemoSeedService.grantOperation(user), user,
                        "GRANT", "local-dev-demo", DemoSeedService.GRANT_REASON));
            }
        }
        try (var context = app.run(args)) {
            assertEquals(user, context.getBean(DemoSeedService.class).seed(DevDemoTest.PASSWORD));
            context.getBean(DemoSeedService.class).verifyPrivilege(user);
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM cats", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feeding_sites", Integer.class));
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertFalse(SystemRoleMaintenance.apply(connection, DemoSeedService.grantOperation(user), user,
                        "GRANT", "local-dev-demo", DemoSeedService.GRANT_REASON));
            }
        }
    }
}
