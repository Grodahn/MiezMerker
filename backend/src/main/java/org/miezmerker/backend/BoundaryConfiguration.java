package org.miezmerker.backend;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

@Configuration
public class BoundaryConfiguration {
    @Bean
    OpenAPI api() {
        return new OpenAPI().info(new Info().title("MiezMerker HTTP API").version("v1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Adaptive BCrypt (Spring Security standard). No custom password crypto.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    CookieCsrfTokenRepository csrfRepository(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .httpOnly(false)
                .path("/")
                .secure(secure)
                .sameSite("Lax"));
        return csrfRepository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrfRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfRepository)));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, CookieCsrfTokenRepository csrfRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy, AppUserRepository users) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        // Public read-only system endpoints do not mutate state.
                        .ignoringRequestMatchers("/api/v1/health", "/api/v1/version", "/api/v1/openapi"))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/health", "/api/v1/version", "/api/v1/openapi")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session", "/api/v1/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/nodes/*/owner")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/credentials/issuer")
                        .permitAll()
                        .anyRequest().access((authentication, context) -> {
                            var current = authentication.get();
                            boolean active = current.isAuthenticated()
                                    && current.getPrincipal() instanceof AppUserDetails principal
                                    && users.findById(principal.getId())
                                            .filter(user -> user.getStatus() == UserStatus.ACTIVE).isPresent();
                            return new AuthorizationDecision(active);
                        }))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .logout(logout -> logout.disable())
                .build();
    }
}
