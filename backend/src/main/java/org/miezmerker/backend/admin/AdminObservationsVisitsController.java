package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.domain.DerivedVisit;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.ObservationVisitQueryService;
import org.miezmerker.backend.service.VisitAggregationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/** Read-only raw/derived views; recomputation delegates exclusively to #10. */
@Controller
public class AdminObservationsVisitsController {
    private final AdminService admins;
    private final ObservationVisitQueryService queries;
    private final VisitAggregationService aggregation;

    public AdminObservationsVisitsController(AdminService admins,
            ObservationVisitQueryService queries, VisitAggregationService aggregation) {
        this.admins = admins;
        this.queries = queries;
        this.aggregation = aggregation;
    }

    // Plain form strings allow neutral validation feedback without echoing errors into logs.
    public static class Filters {
        private String feedingSiteId = "", nodeId = "", chipId = "", sequence = "",
                from = "", to = "";
        private int offset = 0, limit = 50;
        public String getFeedingSiteId() { return feedingSiteId; }
        public void setFeedingSiteId(String v) { feedingSiteId = v; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String v) { nodeId = v; }
        public String getChipId() { return chipId; }
        public void setChipId(String v) { chipId = v; }
        public String getSequence() { return sequence; }
        public void setSequence(String v) { sequence = v; }
        public String getFrom() { return from; }
        public void setFrom(String v) { from = v; }
        public String getTo() { return to; }
        public void setTo(String v) { to = v; }
        public int getOffset() { return offset; }
        public void setOffset(int v) { offset = v; }
        public int getLimit() { return limit; }
        public void setLimit(int v) { limit = v; }
    }

    public record ObservationRow(UUID id, String node, String chip, long sequence,
            Long rawMillis, String clock, String observed, String received,
            String site, UUID deployment, String incarnation, Long monotonic, Integer boot) {}
    public record VisitRow(UUID id, String start, String end, String duration,
            String site, String cat, String chip, int count, String algorithm, int gap,
            UUID first, UUID last) {}

    @GetMapping({"/admin/observations", "/admin/visits"})
    @Transactional(readOnly = true)
    public String list(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            Model model, @ModelAttribute("filters") Filters filters,
            jakarta.servlet.http.HttpServletRequest request) {
        AdminService.AdminOrg active = context(principal, session, model);
        if (active == null) return "redirect:/admin/org";
        boolean raw = request.getServletPath().equals("/admin/observations");
        String route = raw ? "/admin/observations" : "/admin/visits";
        model.addAttribute("raw", raw);
        model.addAttribute("route", route);
        List<Object> rows = new ArrayList<>();
        boolean more = false;
        try {
            if (filters.limit < 1 || filters.limit > 100 || filters.offset < 0
                    || filters.offset > Integer.MAX_VALUE - 101) throw new IllegalArgumentException();
            UUID site = uuid(filters.feedingSiteId);
            UUID node = raw ? uuid(filters.nodeId) : null;
            Long sequence = raw && !blank(filters.sequence) ? Long.valueOf(filters.sequence.trim()) : null;
            if (sequence != null && sequence < 0) throw new IllegalArgumentException();
            Long from = millis(filters.from), to = millis(filters.to);
            if (from != null && to != null && from >= to) throw new IllegalArgumentException();
            filters.chipId = blank(filters.chipId) ? "" : Cat.normalizeChipId(filters.chipId);
            if (filters.chipId.length() > 64) throw new IllegalArgumentException();
            if (raw) {
                var page = queries.observations(principal.getId(), active.id(), node, site,
                        filters.chipId, from, to, filters.limit + 1, filters.offset, true, sequence);
                more = page.size() > filters.limit;
                page.stream().limit(filters.limit).map(AdminObservationsVisitsController::observationRow)
                        .forEach(rows::add);
            } else {
                var page = queries.visits(principal.getId(), active.id(), site, filters.chipId,
                        from, to, filters.limit + 1, filters.offset);
                more = page.size() > filters.limit;
                page.stream().limit(filters.limit).map(AdminObservationsVisitsController::visitRow)
                        .forEach(rows::add);
            }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            model.addAttribute("error", "Filter ungültig. Bitte IDs, Zeitraum und Seitengröße prüfen.");
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
            // Absent and foreign filter resources have the same empty outcome.
        }
        model.addAttribute("rows", rows);
        model.addAttribute("previous", filters.offset > 0 ? pageUrl(route, filters,
                Math.max(0, filters.offset - filters.limit)) : null);
        model.addAttribute("next", more ? pageUrl(route, filters, filters.offset + filters.limit) : null);
        return raw ? "admin/observations" : "admin/visits";
    }

    @PostMapping("/admin/visits/recompute")
    public String recompute(@AuthenticationPrincipal AppUserDetails principal, HttpSession session,
            Model model, @RequestParam UUID formOrganizationId, RedirectAttributes redirect) {
        AdminService.AdminOrg active = context(principal, session, model);
        if (active == null) return "redirect:/admin/org";
        if (!active.id().equals(formOrganizationId)) {
            redirect.addFlashAttribute("error", "Organisation gewechselt. Bitte die Besuchsseite neu öffnen.");
            return "redirect:/admin/visits";
        }
        try {
            var result = aggregation.recompute(principal.getId(), active.id(), null, null);
            redirect.addFlashAttribute("success", "Besuche neu berechnet: " + result.visitCount()
                    + ". Ausgeschlossen: Uhr unbekannt " + result.excludedUnknownClock()
                    + ", Zeit ungültig " + result.excludedImplausibleTime()
                    + ", ohne Futterstelle " + result.excludedUnattributed() + ".");
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT && e.getStatusCode() != HttpStatus.BAD_REQUEST) throw e;
            redirect.addFlashAttribute("error", "Neuberechnung nicht möglich. Bitte erneut versuchen.");
        }
        return "redirect:/admin/visits";
    }

    private AdminService.AdminOrg context(AppUserDetails principal, HttpSession session, Model model) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var orgs = admins.adminOrgs(principal.getId());
        if (orgs.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var active = admins.currentOrgOrNull(principal, session);
        if (active == null) {
            if (orgs.size() > 1) return null;
            active = admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
        }
        model.addAttribute("activeOrg", active);
        model.addAttribute("adminOrgs", orgs);
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        return active;
    }

    private static ObservationRow observationRow(RawObservation o) {
        return new ObservationRow(o.getId(), o.getNode().getNodeId().toString(), o.getChipId(),
                o.getSequence(), o.getObservedAtMs(), o.getClockStatus(),
                AdminSitesController.observedDisplay(o), o.getReceivedAt().toString(),
                o.getFeedingSite() == null ? "Unbekannt" : o.getFeedingSite().getName(),
                o.getDeployment() == null ? null : o.getDeployment().getId(),
                o.getIncarnation(), o.getMonotonicMs(), o.getBootCounter());
    }

    private static VisitRow visitRow(DerivedVisit v) {
        return new VisitRow(v.getId(), v.getStartAt().toString(), v.getEndAt().toString(),
                Duration.between(v.getStartAt(), v.getEndAt()).toString(), v.getFeedingSite().getName(),
                v.getCat() == null ? "Noch keine Katze zugeordnet" : v.getCat().getName(),
                v.getChipId(), v.getObservationCount(), v.getAlgorithmVersion(), v.getGapSeconds(),
                v.getFirstObservation().getId(), v.getLastObservation().getId());
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static UUID uuid(String v) { return blank(v) ? null : UUID.fromString(v.trim()); }
    private static Long millis(String v) {
        return blank(v) ? null : LocalDateTime.parse(v.trim()).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    private static String pageUrl(String route, Filters f, int offset) {
        return UriComponentsBuilder.fromPath(route).queryParam("feedingSiteId", f.feedingSiteId)
                .queryParam("nodeId", f.nodeId).queryParam("chipId", f.chipId)
                .queryParam("sequence", f.sequence).queryParam("from", f.from).queryParam("to", f.to)
                .queryParam("limit", f.limit).queryParam("offset", offset).build().encode().toUriString();
    }
}
