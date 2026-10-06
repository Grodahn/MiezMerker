package org.miezmerker.backend.admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Post-login handling for the server-rendered admin backend (#33).
 *
 * <p>Reuses the same {@code AppUser} + password hashes as the PWA/API (via the
 * shared {@code UserDetailsService} + {@code PasswordEncoder}); no second auth
 * system. Updates {@code last_login_at} like the API login and initialises the
 * server-side active organization context:
 * <ul>
 *   <li>single ACTIVE ADMIN org: select it immediately</li>
 *   <li>multiple: defer to {@code /admin/org} for an explicit validated choice</li>
 *   <li>none (MEMBER-only, PENDING, DISABLED): fall through to {@code /admin/}
 *       which returns a 403 with a reusable layout</li>
 * </ul>
 */
@Component
public class AdminAuthSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    private final AppUserRepository users;
    private final AdminService admins;

    public AdminAuthSuccessHandler(AppUserRepository users, AdminService admins) {
        this.users = users;
        this.admins = admins;
        setDefaultTargetUrl("/admin/");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws ServletException, IOException {
        if (authentication != null && authentication.getPrincipal() instanceof AppUserDetails principal) {
            users.findById(principal.getId()).ifPresent(user -> {
                user.setLastLoginAt(Instant.now());
                users.save(user);
            });
            var orgs = admins.adminOrgs(principal.getId());
            if (orgs.size() == 1) {
                request.getSession().setAttribute(AdminService.SESSION_ORG_KEY, orgs.get(0).id());
            } else {
                // Multiple orgs: keep any previously validated selection only if still valid.
                Object raw = request.getSession().getAttribute(AdminService.SESSION_ORG_KEY);
                boolean stillValid = false;
                if (raw instanceof java.util.UUID current) {
                    stillValid = orgs.stream().anyMatch(o -> o.id().equals(current));
                } else if (raw instanceof String s) {
                    try {
                        java.util.UUID current = java.util.UUID.fromString(s);
                        stillValid = orgs.stream().anyMatch(o -> o.id().equals(current));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!stillValid) {
                    request.getSession().removeAttribute(AdminService.SESSION_ORG_KEY);
                }
            }
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
