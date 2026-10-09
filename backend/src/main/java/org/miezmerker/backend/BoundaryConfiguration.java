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
import org.springframework.util.Assert;
import org.miezmerker.backend.admin.AdminAuthSuccessHandler;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

@Configuration
public class BoundaryConfiguration {
    @Bean
    OpenAPI api() {
        return new OpenAPI().info(new Info().title("MiezMerker HTTP API").version("v1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${miezmerker.security.bcrypt-strength:12}") int strength) {
        // Adaptive BCrypt (Spring Security standard). No custom password crypto.
        // Production default is cost 12; only the explicit test profile lowers
        // it (see application-test.yaml). BCrypt hashes embed their own cost,
        // so matches() keeps verifying hashes created with any other cost.
        Assert.isTrue(strength >= 4 && strength <= 31,
                "miezmerker.security.bcrypt-strength must be between 4 and 31");
        return new BCryptPasswordEncoder(strength);
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
            SessionAuthenticationStrategy sessionAuthenticationStrategy, AppUserRepository users,
            MembershipRepository memberships, AdminAuthSuccessHandler adminSuccessHandler,
            org.miezmerker.backend.security.SystemAuthorizationService systemAuthorization)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        var adminLoginEntry = new LoginUrlAuthenticationEntryPoint("/admin/login");
        var apiUnauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        var apiDenied = new AccessDeniedHandlerImpl();

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        // Public read-only system endpoints do not mutate state.
                        .ignoringRequestMatchers("/api/v1/health", "/api/v1/version", "/api/v1/openapi"))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
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
                        // Admin shell: public login + denied pages, everything else ADMIN-only (#33).
                        .requestMatchers("/admin/login", "/admin/denied").permitAll()
                        // Reserved #92 surface: global privilege, independent of tenant ADMIN.
                        .requestMatchers("/admin/organizations", "/admin/organizations/**")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof AppUserDetails principal
                                    && systemAuthorization.isSysadmin(principal.getId()));
                        })
                        .requestMatchers("/admin", "/admin/**").access((authentication, context) -> {
                            var current = authentication.get();
                            if (!current.isAuthenticated()
                                    || !(current.getPrincipal() instanceof AppUserDetails principal)) {
                                return new AuthorizationDecision(false);
                            }
                            var user = users.findById(principal.getId());
                            if (user.isEmpty() || user.get().getStatus() != UserStatus.ACTIVE) {
                                return new AuthorizationDecision(false);
                            }
                            boolean admin = memberships.findByUserIdWithRefs(principal.getId()).stream()
                                    .anyMatch(m -> m.getRole() == MembershipRole.ADMIN
                                            && m.getStatus() == MembershipStatus.ACTIVE
                                            && m.getOrganization().getStatus() == OrganizationStatus.ACTIVE
                                            && m.getUser().getStatus() == UserStatus.ACTIVE);
                            return new AuthorizationDecision(admin);
                        })
                        .anyRequest().access((authentication, context) -> {
                            var current = authentication.get();
                            boolean active = current.isAuthenticated()
                                    && current.getPrincipal() instanceof AppUserDetails principal
                                    && users.findById(principal.getId())
                                            .filter(user -> user.getStatus() == UserStatus.ACTIVE).isPresent();
                            return new AuthorizationDecision(active);
                        }))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, entryException) -> {
                            String uri = request.getRequestURI();
                            String base = request.getContextPath() + "/admin/";
                            String adminRoot = request.getContextPath() + "/admin";
                            if (uri != null && (uri.startsWith(base) || uri.equals(adminRoot))) {
                                // Expired server-side session (client sends an unknown JSESSIONID):
                                // redirect to a dedicated expired state, otherwise plain login.
                                // Never leak account or tenant details here.
                                boolean expired = request.getRequestedSessionId() != null
                                        && !request.isRequestedSessionIdValid();
                                if (expired) {
                                    response.sendRedirect(
                                            request.getContextPath() + "/admin/login?expired");
                                } else {
                                    adminLoginEntry.commence(request, response, entryException);
                                }
                            } else {
                                apiUnauthorized.commence(request, response, entryException);
                            }
                        })
                        .accessDeniedHandler((request, response, deniedException) -> {
                            String uri = request.getRequestURI();
                            String base = request.getContextPath() + "/admin/";
                            String adminRoot = request.getContextPath() + "/admin";
                            if (uri != null && (uri.startsWith(base) || uri.equals(adminRoot))) {
                                request.getRequestDispatcher("/admin/denied").forward(request, response);
                            } else {
                                apiDenied.handle(request, response, deniedException);
                            }
                        }))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler(adminSuccessHandler)
                        .failureUrl("/admin/login?error")
                        .permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .build();
    }
}
