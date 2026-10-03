package org.miezmerker.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FoundationTest {
    @Value("${local.server.port}") int port;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper mapper = new ObjectMapper();

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void publicSystemEndpointsAndDenyByDefault() throws Exception {
        var health = get("/api/v1/health");
        assertEquals(200, health.statusCode());
        assertEquals("UP", mapper.readTree(health.body()).get("status").asText());
        assertEquals("v1", mapper.readTree(get("/api/v1/version").body()).get("apiVersion").asText());
        // Business endpoints require authentication (401), never leak tenant data.
        assertEquals(401, get("/api/v1/organizations").statusCode());
        // State-changing requests without a CSRF token are never processed as login:
        // the response is 401/403 (never 200) and no session is established.
        var post = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"x@example.org\",\"password\":\"123456789012\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(post.statusCode() == 401 || post.statusCode() == 403,
                "expected CSRF/auth rejection, got " + post.statusCode());
        assertEquals(401, get("/api/v1/auth/session").statusCode());
    }

    @Test void exportActualBackendOpenApi() throws Exception {
        var response = get("/api/v1/openapi");
        assertEquals(200, response.statusCode());
        JsonNode spec = mapper.readTree(response.body());
        assertEquals("v1", spec.get("info").get("version").asText());
        assertEquals("/", spec.get("servers").get(0).get("url").asText());
        assertEquals("getHealth", spec.at("/paths/~1api~1v1~1health/get/operationId").asText());
        assertTrue(spec.at("/components/schemas/VersionResponse").isObject());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), response.body() + "\n");
    }
}
