package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.TemporalVisitService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AdminTemporalVisitsController {
    private final AdminService admins;
    private final TemporalVisitService visits;
    private final AdminTemporalPresentation temporal;
    public AdminTemporalVisitsController(AdminService admins,TemporalVisitService visits,AdminTemporalPresentation temporal) {
        this.admins=admins; this.visits=visits; this.temporal=temporal;
    }
    @GetMapping("/admin/visits/temporal")
    public String read(@AuthenticationPrincipal AppUserDetails principal,HttpSession session,Model model,
            @RequestParam(defaultValue="") String chipId,@RequestParam(defaultValue="0") int offset) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var orgs=admins.adminOrgs(principal.getId());
        if(orgs.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var active=admins.currentOrgOrNull(principal,session);
        if(active==null) {
            if(orgs.size()!=1) return "redirect:/admin/org";
            active=admins.selectOrg(principal.getId(),orgs.getFirst().id(),session);
        }
        model.addAttribute("activeOrg",active); model.addAttribute("adminOrgs",orgs);
        model.addAttribute("userLabel",admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail",admins.emailFor(principal.getId()));
        model.addAttribute("chipId",chipId); model.addAttribute("offset",offset);
        if(!chipId.isBlank()) {
            try {
                var page=visits.read(principal.getId(),active.id(),chipId,offset,20);
                model.addAttribute("page",page);
                model.addAttribute("siteNames",temporal.siteNames(principal.getId(),active.id(),
                        page.items().stream().map(i -> i.feedingSiteId()).toList()));
            }
            catch(ResponseStatusException e) {
                if(e.getStatusCode()==HttpStatus.BAD_REQUEST) model.addAttribute("error","Bitte Chip-ID und Seitenauswahl prüfen.");
                else if(e.getStatusCode()==HttpStatus.CONFLICT) model.addAttribute("error","Neue Beobachtungen eingegangen. Bitte die Seite erneut laden.");
                else if(e.getStatusCode()==HttpStatus.SERVICE_UNAVAILABLE) model.addAttribute("error","Der Zeitverlauf kann derzeit nicht vollständig berechnet werden. Bitte die Verwaltung kontaktieren.");
                else throw e;
            }
        }
        return "admin/temporal-visits";
    }
}
