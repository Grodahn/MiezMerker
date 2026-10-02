package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CookieCsrfTokenRepository csrfRepository;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, AppUserRepository users,
            MembershipRepository memberships, SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CookieCsrfTokenRepository csrfRepository) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.memberships = memberships;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfRepository = csrfRepository;
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @Email @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String password) {}

    @Schema(name = "MembershipView")
    public record MembershipView(
            UUID membershipId, UUID organizationId, String organizationSlug,
            String organizationName, String role, String status) {}

    @Schema(name = "SessionView")
    public record SessionView(
            UUID userId, String email, List<MembershipView> memberships) {}

    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "login", summary = "Email/password login, establishes a server-side session")
    @Transactional
    public SessionView login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String email = AppUser.normalizeEmail(request.email());
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
            sessionAuthenticationStrategy.onAuthentication(auth, httpRequest, httpResponse);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, httpRequest, httpResponse);
            AppUserDetails principal = (AppUserDetails) auth.getPrincipal();
            users.findById(principal.getId()).ifPresent(user -> {
                user.setLastLoginAt(Instant.now());
                users.save(user);
            });
            return sessionFor(principal.getId());
        } catch (AuthenticationException e) {
            // Generic response: no account enumeration, no reason disclosure.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "Invalidate the current server-side session")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        new CsrfLogoutHandler(csrfRepository).logout(request, response, auth);
        new SecurityContextLogoutHandler().logout(request, response, auth);
    }

    @GetMapping(value = "/session", produces = "application/json")
    @Operation(operationId = "getSession", summary = "Current session with user and memberships")
    @Transactional(readOnly = true)
    public SessionView session() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)
                || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        return sessionFor(principal.getId());
    }

    @Schema(name = "CsrfView")
    public record CsrfView(String token, String headerName, String parameterName) {}

    @GetMapping(value = "/csrf", produces = "application/json")
    @Operation(operationId = "getCsrf", summary = "Expose the CSRF token for SPA login flows")
    public CsrfView csrf(HttpServletRequest request) {
        var token = (org.springframework.security.web.csrf.CsrfToken) request.getAttribute("_csrf");
        if (token == null) {
            token = (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(
                    org.springframework.security.web.csrf.CsrfToken.class.getName());
        }
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "csrf unavailable");
        }
        return new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    private SessionView sessionFor(UUID userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account disabled");
        }
        List<MembershipView> views = memberships.findByUserIdWithRefs(userId).stream()
                .filter(m -> m.getOrganization().getStatus() == OrganizationStatus.ACTIVE)
                .map(this::toView)
                .toList();
        return new SessionView(user.getId(), user.getEmail(), views);
    }

    private MembershipView toView(OrganizationMembership m) {
        return new MembershipView(m.getId(), m.getOrganization().getId(),
                m.getOrganization().getSlug(), m.getOrganization().getDisplayName(),
                m.getRole().name(), m.getStatus().name());
    }
}
