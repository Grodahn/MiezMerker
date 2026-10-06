package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.domain.NodeDeployment;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.FeedingSiteService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered feeding-site management (#35).
 *
 * <p>Reuses the shared {@link FeedingSiteService} (same validation and domain
 * semantics as the PWA/REST API from #9/#11) and the #33 Admin infrastructure
 * (ADMIN-only gate, server-side {@code ADMIN_ORG_ID}, reusable layout, CSRF).
 *
 * <p>Every operation is scoped to the validated active Admin organization
 * from the session. Browser-supplied ids never switch tenant context; a
 * foreign feeding-site id yields 404 without revealing existence, name,
 * location, nodes, observations or deployments.
 *
 * <p>Editing only touches master-data columns (name, description, location).
 * Deployment intervals, frozen raw-observation attributions and derived visits
 * are never rewritten.
 */
@Controller
@RequestMapping("/admin/sites")
public class AdminSitesController {
    static final long MAX_OBSERVED_AT_MS_EXCLUSIVE = 9_224_318_016_000_000L;

    private final AdminService admins;
    private final FeedingSiteService feedingSites;
    private final NodeDeploymentRepository deployments;
    private final NodeRepository nodes;
    private final RawObservationRepository observations;

    public AdminSitesController(AdminService admins, FeedingSiteService feedingSites,
            NodeDeploymentRepository deployments, NodeRepository nodes,
            RawObservationRepository observations) {
        this.admins = admins;
        this.feedingSites = feedingSites;
        this.deployments = deployments;
        this.nodes = nodes;
        this.observations = observations;
    }

    public record SiteRow(UUID id, String name, String description,
            String locationLabel, Double locationLat, Double locationLng,
            int currentNodeCount) {}

    public record CurrentNodeRow(String nodeId, String firmwareVersion,
            String statusNote, String lastContactAt) {}

    public record DeploymentRow(UUID id, String nodeId, String validFrom,
            String validUntil, boolean open) {}

    public record ObservationRow(UUID id, String chipId, String clockStatus,
            String observedDisplay, boolean clockUnknown, String receivedAt,
            String nodeId, long sequence, String deploymentId) {}

    // --- List ---

    @GetMapping({"", "/"})
    @Transactional(readOnly = true)
    public String list(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        List<FeedingSite> all = feedingSites.list(principal.getId(), ctx.org().id());
        Instant now = Instant.now();
        List<NodeDeployment> allDeployments =
                deployments.findByOrganizationId(ctx.org().id());
        List<SiteRow> rows = new ArrayList<>();
        for (FeedingSite s : all) {
            int current = 0;
            for (NodeDeployment d : allDeployments) {
                if (d.getFeedingSite().getId().equals(s.getId()) && covers(d, now)) {
                    current++;
                }
            }
            rows.add(new SiteRow(s.getId(), s.getName(), s.getDescription(),
                    s.getLocationLabel(), s.getLocationLat(), s.getLocationLng(),
                    current));
        }
        rows.sort(Comparator.comparing(SiteRow::name, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("sites", rows);
        model.addAttribute("siteCount", rows.size());
        return "admin/sites-list";
    }

    // --- Create ---

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        if (!model.containsAttribute("formName")) {
            model.addAttribute("formName", "");
            model.addAttribute("formDescription", "");
            model.addAttribute("formLocationLabel", "");
            model.addAttribute("formLocationLat", "");
            model.addAttribute("formLocationLng", "");
        }
        model.addAttribute("mode", "create");
        model.addAttribute("formAction", "/admin/sites");
        model.addAttribute("isEdit", false);
        return "admin/sites-form";
    }

    @PostMapping({"", "/"})
    @Transactional
    public String create(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "locationLabel", required = false) String locationLabel,
            @RequestParam(value = "locationLat", required = false) String locationLat,
            @RequestParam(value = "locationLng", required = false) String locationLng,
            RedirectAttributes redirect) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        List<String> errors = new ArrayList<>();
        String cleanName = validateName(name, errors);
        String cleanDescription = validateOptional(description, 2000,
                "Beschreibung darf höchstens 2000 Zeichen haben.", errors);
        String cleanLabel = validateOptional(locationLabel, 255,
                "Ort darf höchstens 255 Zeichen haben.", errors);
        Double[] coords = parseCoords(locationLat, locationLng, false, null, errors);
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            model.addAttribute("formName", name == null ? "" : name);
            model.addAttribute("formDescription", description == null ? "" : description);
            model.addAttribute("formLocationLabel",
                    locationLabel == null ? "" : locationLabel);
            model.addAttribute("formLocationLat",
                    locationLat == null ? "" : locationLat);
            model.addAttribute("formLocationLng",
                    locationLng == null ? "" : locationLng);
            model.addAttribute("mode", "create");
            model.addAttribute("formAction", "/admin/sites");
            model.addAttribute("isEdit", false);
            return "admin/sites-form";
        }
        FeedingSite created = feedingSites.create(principal.getId(), ctx.org().id(),
                cleanName, cleanDescription, coords[0], coords[1], cleanLabel);
        redirect.addFlashAttribute("success", "Futterstelle angelegt.");
        return "redirect:/admin/sites/" + created.getId();
    }

    // --- Detail ---

    @GetMapping("/{siteId}")
    @Transactional(readOnly = true)
    public String detail(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @PathVariable UUID siteId) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        FeedingSite site = loadOwn(principal, ctx, siteId);
        fillDetail(principal, ctx, site, model);
        return "admin/sites-detail";
    }

    // --- Edit ---

    @GetMapping("/{siteId}/edit")
    @Transactional(readOnly = true)
    public String editForm(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @PathVariable UUID siteId) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        FeedingSite site = loadOwn(principal, ctx, siteId);
        if (!model.containsAttribute("formName")) {
            model.addAttribute("formName", site.getName() == null ? "" : site.getName());
            model.addAttribute("formDescription",
                    site.getDescription() == null ? "" : site.getDescription());
            model.addAttribute("formLocationLabel",
                    site.getLocationLabel() == null ? "" : site.getLocationLabel());
            model.addAttribute("formLocationLat",
                    site.getLocationLat() == null ? "" : site.getLocationLat().toString());
            model.addAttribute("formLocationLng",
                    site.getLocationLng() == null ? "" : site.getLocationLng().toString());
            model.addAttribute("formClearLocation", false);
        }
        model.addAttribute("siteId", site.getId());
        model.addAttribute("siteName", site.getName());
        model.addAttribute("mode", "edit");
        model.addAttribute("formAction", "/admin/sites/" + site.getId());
        model.addAttribute("isEdit", true);
        return "admin/sites-form";
    }

    @PostMapping("/{siteId}")
    @Transactional
    public String update(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @PathVariable UUID siteId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "locationLabel", required = false) String locationLabel,
            @RequestParam(value = "locationLat", required = false) String locationLat,
            @RequestParam(value = "locationLng", required = false) String locationLng,
            @RequestParam(value = "clearLocation", required = false) String clearLocation,
            RedirectAttributes redirect) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        FeedingSite existing = loadOwn(principal, ctx, siteId);
        boolean clear = "on".equalsIgnoreCase(clearLocation)
                || "true".equalsIgnoreCase(clearLocation);
        List<String> errors = new ArrayList<>();
        String cleanName = validateName(name, errors);
        // Length checks for the raw inputs; the shared service re-validates
        // and converts blank to null. Raw (not pre-cleaned) values are passed
        // so an explicitly cleared field (empty string) really clears instead
        // of being mistaken for "no change".
        validateOptional(description, 2000,
                "Beschreibung darf höchstens 2000 Zeichen haben.", errors);
        String rawLabelForService = null;
        if (!clear) {
            validateOptional(locationLabel, 255,
                    "Ort darf höchstens 255 Zeichen haben.", errors);
            rawLabelForService = locationLabel;
        }
        Double[] coords;
        if (clear) {
            coords = new Double[] {null, null};
        } else {
            coords = parseCoords(locationLat, locationLng, true, existing, errors);
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            model.addAttribute("formName", name == null ? "" : name);
            model.addAttribute("formDescription",
                    description == null ? "" : description);
            model.addAttribute("formLocationLabel",
                    locationLabel == null ? "" : locationLabel);
            model.addAttribute("formLocationLat",
                    locationLat == null ? "" : locationLat);
            model.addAttribute("formLocationLng",
                    locationLng == null ? "" : locationLng);
            model.addAttribute("formClearLocation", clear);
            model.addAttribute("siteId", existing.getId());
            model.addAttribute("siteName", existing.getName());
            model.addAttribute("mode", "edit");
            model.addAttribute("formAction", "/admin/sites/" + existing.getId());
            model.addAttribute("isEdit", true);
            return "admin/sites-form";
        }
        if (clear) {
            feedingSites.update(principal.getId(), ctx.org().id(), existing.getId(),
                    cleanName, description, null, null, null, true);
        } else {
            feedingSites.update(principal.getId(), ctx.org().id(), existing.getId(),
                    cleanName, description, coords[0], coords[1],
                    rawLabelForService, false);
        }
        redirect.addFlashAttribute("success", "Futterstelle gespeichert.");
        return "redirect:/admin/sites/" + existing.getId();
    }

    // --- Helpers ---

    private record ActiveContext(AdminService.AdminOrg org,
            List<AdminService.AdminOrg> orgs) {}

    private ActiveContext requireContext(AppUserDetails principal, HttpSession session,
            Model model) {
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
                return null;
            }
            active = admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
        }
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("activeOrg", active);
        model.addAttribute("adminOrgs", orgs);
        return new ActiveContext(active, orgs);
    }

    private FeedingSite loadOwn(AppUserDetails principal, ActiveContext ctx,
            UUID siteId) {
        // Tenant-scoped read: foreign ids are indistinguishable from absent
        // ones (404, no name/location/nodes/observations leak).
        return feedingSites.get(principal.getId(), ctx.org().id(), siteId);
    }

    private void fillDetail(AppUserDetails principal, ActiveContext ctx,
            FeedingSite site, Model model) {
        UUID orgId = ctx.org().id();
        Instant now = Instant.now();
        List<NodeDeployment> siteDeployments =
                deployments.findByOrganizationIdAndFeedingSiteId(orgId, site.getId());
        siteDeployments.sort(
                Comparator.comparing(NodeDeployment::getValidFrom).reversed());

        List<CurrentNodeRow> current = new ArrayList<>();
        for (NodeDeployment d : siteDeployments) {
            if (!covers(d, now)) {
                continue;
            }
            var node = d.getNode();
            // Defensive: only nodes still owned by this org are shown.
            if (node.getOrganization() == null
                    || !node.getOrganization().getId().equals(orgId)) {
                continue;
            }
            current.add(new CurrentNodeRow(node.getNodeId().toString(),
                    node.getFirmwareVersion(), node.getStatusNote(),
                    node.getLastContactAt() == null ? null
                            : node.getLastContactAt().toString()));
        }
        current.sort(Comparator.comparing(CurrentNodeRow::nodeId));

        List<DeploymentRow> history = new ArrayList<>();
        for (NodeDeployment d : siteDeployments) {
            history.add(new DeploymentRow(d.getId(),
                    d.getNode().getNodeId().toString(),
                    d.getValidFrom().toString(),
                    d.getValidUntil() == null ? null : d.getValidUntil().toString(),
                    d.getValidUntil() == null));
        }

        var page = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "receivedAt")
                        .and(Sort.by(Sort.Direction.DESC, "sequence")));
        List<RawObservation> recent =
                observations.findByOrganizationIdAndFeedingSiteId(orgId, site.getId(),
                        page);
        // Repository ordering by receivedAt/sequence is the contract; re-sort
        // defensively newest-first so the page never implies a new aggregation.
        recent.sort(Comparator.comparing(RawObservation::getReceivedAt).reversed()
                .thenComparing(Comparator.comparingLong(RawObservation::getSequence)
                        .reversed()));
        List<ObservationRow> obsRows = new ArrayList<>();
        for (RawObservation o : recent) {
            obsRows.add(new ObservationRow(o.getId(), o.getChipId(),
                    o.getClockStatus(), observedDisplay(o),
                    "UNKNOWN".equals(o.getClockStatus()),
                    o.getReceivedAt().toString(),
                    o.getNode().getNodeId().toString(), o.getSequence(),
                    o.getDeployment() == null ? null
                            : o.getDeployment().getId().toString()));
        }
        long totalObs =
                observations.countByOrganizationIdAndFeedingSiteId(orgId, site.getId());

        model.addAttribute("siteId", site.getId());
        model.addAttribute("siteName", site.getName());
        model.addAttribute("siteDescription", site.getDescription());
        model.addAttribute("siteLocationLabel", site.getLocationLabel());
        model.addAttribute("siteLocationLat", site.getLocationLat());
        model.addAttribute("siteLocationLng", site.getLocationLng());
        model.addAttribute("siteCreatedAt", site.getCreatedAt().toString());
        model.addAttribute("siteUpdatedAt", site.getUpdatedAt().toString());
        model.addAttribute("currentNodes", current);
        model.addAttribute("currentNodeCount", current.size());
        model.addAttribute("deployments", history);
        model.addAttribute("deploymentCount", history.size());
        model.addAttribute("recentObservations", obsRows);
        model.addAttribute("observationTotal", totalObs);
    }

    static boolean covers(NodeDeployment d, Instant now) {
        if (d.getValidFrom() != null && now.isBefore(d.getValidFrom())) {
            return false;
        }
        return d.getValidUntil() == null || now.isBefore(d.getValidUntil());
    }

    static String observedDisplay(RawObservation o) {
        // Honest clock distinction (#11): UNKNOWN never becomes a precise
        // timestamp; implausible values are shown as raw, never corrected.
        if ("UNKNOWN".equals(o.getClockStatus()) || o.getObservedAtMs() == null) {
            return "Uhrzeit unbekannt";
        }
        Long ms = o.getObservedAtMs();
        String clock = o.getClockStatus();
        boolean reliable = ("SYNCED".equals(clock) || "RTC_ONLY".equals(clock)
                || "KNOWN".equals(clock))
                && ms > 0 && ms < MAX_OBSERVED_AT_MS_EXCLUSIVE;
        if (reliable) {
            try {
                return Instant.ofEpochMilli(ms).toString();
            } catch (Exception e) {
                return "Ungültige Zeit (Rohwert: " + ms + ")";
            }
        }
        return "Ungültige Zeit (Rohwert: " + ms + ")";
    }

    private static String validateName(String name, List<String> errors) {
        if (name == null || name.isBlank()) {
            errors.add("Bitte einen Namen eingeben.");
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            errors.add("Name darf höchstens 255 Zeichen haben.");
            return null;
        }
        return trimmed;
    }

    private static String validateOptional(String value, int max, String message,
            List<String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            errors.add(message);
            return null;
        }
        return trimmed;
    }

    /**
     * Parses the coordinate pair. Empty/blank means absent. Returns
     * {@code [lat, lng]} with nulls for absent. For edits with existing
     * coordinates, clearing both without the checkbox is an error (mirrors
     * the PWA) so history is never cleared by accident.
     */
    private static Double[] parseCoords(String latRaw, String lngRaw, boolean isEdit,
            FeedingSite existing, List<String> errors) {
        boolean latEmpty = latRaw == null || latRaw.isBlank();
        boolean lngEmpty = lngRaw == null || lngRaw.isBlank();
        if (latEmpty && lngEmpty) {
            if (isEdit && existing != null && existing.getLocationLat() != null) {
                errors.add("Zum Entfernen bitte „Standort vollständig entfernen“ auswählen.");
            }
            return new Double[] {null, null};
        }
        if (latEmpty != lngEmpty) {
            errors.add("Breiten- und Längengrad bitte zusammen angeben.");
            return new Double[] {null, null};
        }
        Double lat;
        Double lng;
        try {
            lat = Double.parseDouble(latRaw.trim());
            lng = Double.parseDouble(lngRaw.trim());
        } catch (NumberFormatException e) {
            errors.add("Breiten-/Längengrad müssen Zahlen sein.");
            return new Double[] {null, null};
        }
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            errors.add("Breitengrad muss zwischen -90 und 90 liegen.");
            return new Double[] {null, null};
        }
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) {
            errors.add("Längengrad muss zwischen -180 und 180 liegen.");
            return new Double[] {null, null};
        }
        return new Double[] {lat, lng};
    }
}
