package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.security.AppUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/organization/settings")
public class AdminOrganizationSettingsController {
    private final AdminService admins;
    private final OrganizationSettingsService settings;

    public AdminOrganizationSettingsController(AdminService admins, OrganizationSettingsService settings) {
        this.admins = admins;
        this.settings = settings;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var orgs = admins.adminOrgs(principal.getId());
        if (orgs.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var org = admins.currentOrgOrNull(principal, session);
        if (org == null) {
            if (orgs.size() != 1) return "redirect:/admin/org";
            org = admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
        }
        model.addAttribute("activeOrg", org);
        model.addAttribute("adminOrgs", orgs);
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("settings", settings.read(principal.getId(), org.id()));
        model.addAttribute("scopes", List.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL));
        return "admin/organization-settings";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            @RequestParam UUID formOrganizationId, @RequestParam String version,
            @RequestParam MultiValueMap<String, String> form, RedirectAttributes flash) {
        var org = admins.requireActiveOrg(principal, session);
        if (!org.id().equals(formOrganizationId)) {
            flash.addFlashAttribute("error", "Organisation wurde gewechselt. Bitte aktuelle Einstellungen erneut prüfen.");
            return "redirect:/admin/organization/settings";
        }
        try {
            Map<ShareScope, OrganizationSettingsService.Choice> choices = new EnumMap<>(ShareScope.class);
            if (!form.containsKey("hidden")) {
                for (ShareScope scope : List.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL)) {
                    ShareAudience audience = ShareAudience.valueOf(form.getFirst(scope.name()));
                    List<UUID> recipients = audience == ShareAudience.ALLOWLIST
                            ? form.getOrDefault(scope.name() + "Recipients", List.of()).stream().map(UUID::fromString).toList()
                            : List.of();
                    choices.put(scope, new OrganizationSettingsService.Choice(audience, recipients));
                }
            }
            settings.save(principal.getId(), org.id(), version, form.containsKey("hidden"), choices,
                    form.containsKey("confirmed"), form.containsKey("revokePhoto"));
            flash.addFlashAttribute("success", "Einstellungen gespeichert. Widerrufe gelten für nachfolgende Backend-Abfragen.");
        } catch (IllegalArgumentException | NullPointerException e) {
            flash.addFlashAttribute("error", "Ungültige Auswahl. Bitte aktuelle Einstellungen erneut prüfen.");
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.BAD_REQUEST && e.getStatusCode() != HttpStatus.CONFLICT) throw e;
            flash.addFlashAttribute("error", "invalid recipient".equals(e.getReason())
                    ? "Empfängerauswahl ist nicht mehr verfügbar. Bitte erneut prüfen." : e.getReason());
        } catch (org.springframework.dao.TransientDataAccessException e) {
            flash.addFlashAttribute("error", "Eine parallele Änderung hat das Speichern verhindert. Bitte erneut prüfen.");
        }
        // Fetch fresh authorized state after errors; never echo stale recipient IDs/names.
        return "redirect:/admin/organization/settings";
    }
}
