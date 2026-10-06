package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-rendered member management (#34) inside the Admin backend.
 *
 * <p>Reuses {@link MemberService} (#16 + #32) instead of calling the REST API
 * over HTTP. The active organization comes exclusively from
 * {@link AdminService} (server-side session); membership ids are resolved
 * strictly inside that boundary (no IDOR, no enumeration).
 *
 * <p>Supported maintenance (all POST + CSRF, never GET):
 * <ul>
 *   <li>role ({@code ADMIN|MEMBER})</li>
 *   <li>status ({@code PENDING|ACTIVE|DISABLED})</li>
 *   <li>displayName (global {@code AppUser} identity, affects all orgs;
 *       blank clears to {@code NULL})</li>
 * </ul>
 *
 * <p>No last-ADMIN guard exists in the current domain (#16 semantics preserved):
 * demoting/disabling the final ADMIN is allowed and documented.
 *
 * <p>Future #19 invite-by-email will naturally live here (e.g. a
 * {@code Mitglied einladen} action posting to a new endpoint). This controller
 * intentionally exposes no mail/token/activation flow.
 */
@Controller
@RequestMapping("/admin/members")
public class AdminMembersController {
    private final AdminService admins;
    private final MemberService members;

    public AdminMembersController(AdminService admins, MemberService members) {
        this.admins = admins;
        this.members = members;
    }

    public record MemberRow(UUID membershipId, UUID userId, String email, String displayName,
            String displayOrEmail, String role, String status) {}

    private static MemberRow toRow(OrganizationMembership m) {
        String name = m.getUser().getDisplayName();
        // #32 fallback: show email when no name is set; never persist email-derived names.
        String fallback = (name != null && !name.trim().isEmpty()) ? name : m.getUser().getEmail();
        return new MemberRow(m.getId(), m.getUser().getId(), m.getUser().getEmail(), name,
                fallback, m.getRole().name(), m.getStatus().name());
    }

    @GetMapping({"", "/"})
    public String list(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            Model model,
            @RequestParam(value = "success", required = false) String success,
            @RequestParam(value = "error", required = false) String error) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        List<AdminService.AdminOrg> orgs = admins.adminOrgs(principal.getId());
        if (orgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
        }
        AdminService.AdminOrg active = admins.currentOrgOrNull(principal, session);
        if (active == null) {
            if (orgs.size() > 1) {
                return "redirect:/admin/org";
            }
            active = admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
        }
        List<MemberRow> rows = members.listMembers(principal.getId(), active.id()).stream()
                .map(AdminMembersController::toRow)
                .toList();
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("activeOrg", active);
        model.addAttribute("adminOrgs", orgs);
        model.addAttribute("members", rows);
        if (success != null) {
            model.addAttribute("success", success);
        }
        if (error != null) {
            model.addAttribute("error", error);
        }
        return "admin/members";
    }

    /**
     * Single maintenance endpoint for role/status/displayName.
     * Form fields are optional; at least one must be provided.
     * {@code displayName} absent ({@code null}) means no change; present
     * (even blank) updates globally (blank clears to {@code NULL} per #32).
     */
    @PostMapping("/{membershipId}")
    public String update(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            @PathVariable UUID membershipId,
            @RequestParam(value = "role", required = false) String roleParam,
            @RequestParam(value = "status", required = false) String statusParam,
            @RequestParam(value = "displayName", required = false) String displayNameParam) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        AdminService.AdminOrg active = admins.requireActiveOrg(principal, session);
        UUID orgId = active.id();

        boolean hasRole = roleParam != null && !roleParam.trim().isEmpty();
        boolean hasStatus = statusParam != null && !statusParam.trim().isEmpty();
        boolean hasName = displayNameParam != null;
        if (!hasRole && !hasStatus && !hasName) {
            return redirectError("Keine Änderung angegeben.");
        }

        MembershipRole role = null;
        MembershipStatus status = null;
        if (hasRole) {
            try {
                role = MembershipRole.valueOf(roleParam.trim());
            } catch (IllegalArgumentException e) {
                return redirectError("Ungültige Rolle.");
            }
        }
        if (hasStatus) {
            try {
                status = MembershipStatus.valueOf(statusParam.trim());
            } catch (IllegalArgumentException e) {
                return redirectError("Ungültiger Status.");
            }
        }

        try {
            members.updateMember(principal.getId(), orgId, membershipId, role, status,
                    hasName ? displayNameParam : null);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // No leak: same 404 as unknown id, no member details.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
            }
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                return redirectError("Ungültiger Anzeigename (max. 255 Zeichen).");
            }
            throw e;
        }
        return "redirect:/admin/members?success=" + urlEncode("Mitglied aktualisiert.");
    }

    private static String redirectError(String message) {
        return "redirect:/admin/members?error=" + urlEncode(message);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Fehler";
        }
    }
}
