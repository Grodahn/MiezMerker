package org.miezmerker.backend;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class BoundaryConfiguration {
    @Bean
    OpenAPI api() {
        return new OpenAPI().info(new Info().title("MiezMerker HTTP API").version("v1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // No business endpoints or login yet. Keep CSRF enabled for future cookie sessions.
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health", "/api/v1/version", "/api/v1/openapi").permitAll()
                .anyRequest().denyAll()).build();
    }
}
