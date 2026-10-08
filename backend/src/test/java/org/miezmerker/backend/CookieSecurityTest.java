package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.repo.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

// No test profile: exercise the production cookie defaults with an isolated H2 database.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:secure-cookies;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@Tag("auth")
class CookieSecurityTest {
    @Value("${local.server.port}") int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void sessionAndCsrfCookiesAreSecureByDefault() throws Exception {
        var user = users.save(new AppUser("secure-cookie@example.org", passwords.encode("cookie-test-password")));
        var client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/v1/auth";
        try {
            var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrf.statusCode());
            String csrfCookie = csrf.headers().allValues("set-cookie").stream()
                    .filter(cookie -> cookie.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
            assertTrue(csrfCookie.contains("Secure"), csrfCookie);
            assertTrue(csrfCookie.contains("SameSite=Lax"), csrfCookie);
            String token = new ObjectMapper().readTree(csrf.body()).get("token").asText();
            // Supply the Secure cookie explicitly to test flags without a TLS test server.
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Cookie", csrfCookie.substring(0, csrfCookie.indexOf(';')))
                    .header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"secure-cookie@example.org\",\"password\":\"cookie-test-password\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), login.body());
            String sessionCookie = login.headers().allValues("set-cookie").stream()
                    .filter(cookie -> cookie.startsWith("JSESSIONID=")).findFirst().orElseThrow();
            assertTrue(sessionCookie.contains("Secure"), sessionCookie);
            assertTrue(sessionCookie.contains("HttpOnly"), sessionCookie);
            assertTrue(sessionCookie.contains("SameSite=Lax"), sessionCookie);
        } finally {
            users.deleteById(user.getId());
        }
    }
}
