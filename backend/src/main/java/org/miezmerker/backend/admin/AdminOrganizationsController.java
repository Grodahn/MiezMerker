package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.SystemAuthorizationService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered SYSADMIN organization management (#92, ADR 0016).
 *
 * <p>Surface: {@code /admin/organizations/**}. The security filter already
 * reserves these paths for ACTIVE global SYSADMIN; every handler additionally
 * calls {@link SystemAuthorizationService#requireSysadmin}, and
 * {@link SysadminOrganizationService} repeats the check on every operation.
 * SYSADMIN here administers organization metadata and Orgaadmin memberships
 * only: it never grants access to tenant data (Cats, FeedingSites, Nodes,
 * observations, visits), which keep requiring an ACTIVE membership.
 *
 * <p>All mutations are POST + CSRF, never GET. Deactivation and deletion
 * require explicit confirmation; deletion uses safe retention (deactivation
 * in place, no row deletes) and documents the remaining operator work.
 */
@Controller
@RequestMapping("/admin/organizations")
public class AdminOrganizationsController {
    private final SystemAuthorizationService system;
    private final AdminService admins;
    private final SysadminOrganizationService organizations;

    public AdminOrganizationsController(SystemAuthorizationService system, AdminService admins,
            SysadminOrganizationService organizations) {
        this.system = system;
        this.admins = admins;
        this.organizations = organizations;
    }

    private static AppUserDetails authenticated(AppUserDetails principal,
            SystemAuthorizationService system) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        system.requireSysadmin(principal);
        return principal;
    }

    @GetMapping({"", "/"})
    public String list(@AuthenticationPrincipal AppUserDetails principal,
            HttpServletResponse response, Model model) {
        authenticated(principal, system);
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("organizations", organizations.listOrganizations(principal));
        return "admin/organizations";
    }

    @PostMapping({"", "/"})
    public String create(@AuthenticationPrincipal AppUserDetails principal,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "publicContact", required = false) String publicContact,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            var created = organizations.createOrganization(principal, slug, displayName,
                    publicContact);
            flash.addFlashAttribute("success",
                    "Organisation „" + created.displayName() + "“ angelegt (versteckt, ohne Freigaben).");
            return "redirect:/admin/organizations/" + created.id();
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
            return "redirect:/admin/organizations";
        }
    }

    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id, HttpServletResponse response, Model model) {
        authenticated(principal, system);
        response.setHeader("Cache-Control", "no-store");
        var org = organizations.getOrganization(principal, id);
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("org", org);
        model.addAttribute("members", organizations.listMembers(principal, id));
        model.addAttribute("policyCount", organizations.outgoingPolicyCount(principal, id));
        model.addAttribute("audit", organizations.recentAudit(principal, id));
        return "admin/organization-detail";
    }

    @PostMapping("/{id}/rename")
    public String rename(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "publicContact", required = false) String publicContact,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            var renamed = organizations.renameOrganization(principal, id, displayName,
                    publicContact);
            flash.addFlashAttribute("success",
                    "Organisation umbenannt in „" + renamed.displayName()
                            + "“. Technische Kennung unverändert.");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id,
            @RequestParam(value = "confirmed", required = false) String confirmed,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            organizations.deactivateOrganization(principal, id, confirmed != null);
            flash.addFlashAttribute("success",
                    "Organisation deaktiviert. Zugriffe und Freigaben sind sofort gesperrt.");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id,
            @RequestParam(value = "confirmed", required = false) String confirmed,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            organizations.reactivateOrganization(principal, id, confirmed != null);
            flash.addFlashAttribute("success",
                    "Organisation reaktiviert. Frühere Freigaben wurden nicht wiederhergestellt.");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id,
            @RequestParam(value = "slugConfirmation", required = false) String slugConfirmation,
            @RequestParam(value = "confirmed", required = false) String confirmed,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            organizations.requestDeletion(principal, id, slugConfirmation, confirmed != null);
            flash.addFlashAttribute("success",
                    "Löschanfrage gespeichert: Organisation ist deaktiviert und wird aufbewahrt. "
                            + "Katzen-, Beobachtungs-, Besuchs-, Geräte- und Mitgliedschaftsdaten "
                            + "bleiben erhalten; eine endgültige Löschung erfolgt nur per "
                            + "dokumentiertem Operator-Verfahren.");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    @PostMapping("/{id}/admins")
    public String assignAdmin(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id,
            @RequestParam(value = "email", required = false) String email,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            var assigned = organizations.assignAdmin(principal, id, email);
            flash.addFlashAttribute("success",
                    assigned.email() + " ist jetzt Orgaadmin dieser Organisation.");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    @PostMapping("/{id}/admins/{userId}/revoke")
    public String revokeAdmin(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable("id") UUID id, @PathVariable("userId") UUID userId,
            RedirectAttributes flash) {
        authenticated(principal, system);
        try {
            var revoked = organizations.revokeAdmin(principal, id, userId);
            flash.addFlashAttribute("success",
                    "Orgaadmin-Status von " + revoked.email()
                            + " entzogen (bleibt Mitglied).");
        } catch (ResponseStatusException e) {
            flash.addFlashAttribute("error", message(e));
        }
        return "redirect:/admin/organizations/" + id;
    }

    private static String message(ResponseStatusException e) {
        // Service reasons are already user-facing German messages, including
        // "Organisation nicht gefunden." for forged ids.
        String reason = e.getReason();
        return reason != null && !reason.isBlank() ? reason : "Ungültige Eingabe.";
    }
}
