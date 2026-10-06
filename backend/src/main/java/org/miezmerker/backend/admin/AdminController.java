package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-rendered ADMIN-only backoffice shell (#33).
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /admin/login} - public login page (no PWA shell)</li>
 *   <li>{@code POST /admin/login} - handled by Spring Security form login,
 *       same AppUser + BCrypt hashes as the PWA/API</li>
 *   <li>{@code POST /admin/logout} - handled by Spring Security logout</li>
 *   <li>{@code GET /admin/} - dashboard with navigation for all backoffice areas</li>
 *   <li>{@code GET /admin/org} + {@code POST /admin/org} - validated org selection</li>
 * </ul>
 * Detailed members/sites/cats/observations/visits pages arrive in #34-#37;
 * this ticket only provides the reusable layout/navigation plus placeholder
 * stubs so links never dangle.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {
    private final AdminService admins;

    public AdminController(AdminService admins) {
        this.admins = admins;
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal AppUserDetails principal, Model model,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "expired", required = false) String expired) {
        // Already authenticated: admins skip the form, everyone else gets the
        // understandable 403 state instead of a confusing second login form.
        if (principal != null) {
            if (admins.isAdmin(principal.getId())) {
                return "redirect:/admin/";
            }
            return "redirect:/admin/denied";
        }
        if (error != null) {
            model.addAttribute("loginError", "Anmeldung fehlgeschlagen. E-Mail und Passwort prüfen.");
        }
        if (logout != null) {
            model.addAttribute("loginInfo", "Erfolgreich abgemeldet.");
        }
        if (expired != null) {
            model.addAttribute("loginInfo", "Sitzung abgelaufen. Bitte erneut anmelden.");
        }
        return "admin/login";
    }

    // A security forward preserves the rejected request's HTTP method. Handle
    // every method so CSRF denials remain 403, including PUT/PATCH/DELETE.
    @RequestMapping("/denied")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String denied(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        // Understandable 403 state (no tenant leak: no org names, no IDs).
        if (principal != null) {
            model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
            model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        }
        return "admin/denied";
    }

    @GetMapping({"", "/"})
    public String dashboard(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        // Single fetch: an empty ADMIN-org list is the 403 gate.
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
        fillCommon(principal, active, orgs, model);
        return "admin/dashboard";
    }

    @GetMapping("/org")
    public String orgSelect(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        List<AdminService.AdminOrg> orgs = admins.adminOrgs(principal.getId());
        if (orgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
        }
        if (orgs.size() <= 1) {
            // Nothing to choose: ensure the single org is active and go back.
            if (orgs.size() == 1) {
                admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
            }
            return "redirect:/admin/";
        }
        AdminService.AdminOrg active = admins.currentOrgOrNull(principal, session);
        fillCommon(principal, active, orgs, model);
        return "admin/org-select";
    }

    @PostMapping("/org")
    public String orgSwitch(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session,
            @RequestParam("organizationId") UUID organizationId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        // Validated switch: TenantService.requireAdmin rejects foreign / non-ADMIN orgs.
        // No client-supplied id is ever trusted without this check.
        admins.selectOrg(principal.getId(), organizationId, session);
        return "redirect:/admin/";
    }

    // --- Placeholder stubs for #34-#37 (reusable layout, no detailed logic yet) ---

    @GetMapping("/members")
    public String members(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        return placeholder(principal, session, model, "Mitglieder",
                "Mitgliederverwaltung folgt in #34 (Name, E-Mail, Rolle, Status).");
    }

    @GetMapping("/sites")
    public String sites(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        return placeholder(principal, session, model, "Futterstellen",
                "Futterstellenverwaltung folgt in #35 (Liste, Anlage, Detail, Nodes, Deployments).");
    }

    @GetMapping("/cats")
    public String cats(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        return placeholder(principal, session, model, "Katzen",
                "Katzenverwaltung folgt in #37 (Liste, Chips, Status, Sichtungen).");
    }

    @GetMapping("/observations")
    public String observations(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        return placeholder(principal, session, model, "Rohbeobachtungen",
                "Rohbeobachtungen folgen in #36 (Liste, Filter, Clock-Status, Site-Zuordnung).");
    }

    @GetMapping("/visits")
    public String visits(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        return placeholder(principal, session, model, "Besuche",
                "Besuche folgen in #36 (Start/Ende/Dauer, Katze/Chip, Provenance, Recompute).");
    }

    private String placeholder(AppUserDetails principal, HttpSession session, Model model,
            String section, String hint) {
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
        fillCommon(principal, active, orgs, model);
        model.addAttribute("section", section);
        model.addAttribute("hint", hint);
        return "admin/placeholder";
    }

    private void fillCommon(AppUserDetails principal, AdminService.AdminOrg active,
            List<AdminService.AdminOrg> orgs, Model model) {
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("activeOrg", active);
        model.addAttribute("adminOrgs", orgs);
    }
}
