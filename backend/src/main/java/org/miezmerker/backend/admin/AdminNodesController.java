package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.domain.NodeDeployment;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.DeploymentService;
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
 * Server-rendered Napf (Node) management (#52).
 *
 * <p>User-facing term is <em>Napf</em>; internally the existing {@code Node}
 * entity ({@link NodeDevice}) is kept. No second Bowl entity, no parallel
 * assignment model, no new deployment persistence.
 *
 * <p>Reuses the #33 Admin infrastructure (ADMIN-only gate, server-side
 * {@code ADMIN_ORG_ID}, reusable layout, CSRF), the persisted
 * {@code displayName} semantics from #50 (trim, max 100, nullable/blank,
 * duplicates allowed) and the atomic {@link DeploymentService#move} behavior
 * with its ADMIN-only authorization from #51.
 *
 * <p>Every operation is scoped to the validated active Admin organization
 * from the session. Browser-supplied ids never switch tenant context; a
 * foreign node or feeding-site id yields 404 without revealing existence,
 * name, or history.
 */
@Controller
@RequestMapping("/admin/nodes")
public class AdminNodesController {
    static final String UNNAMED_FALLBACK = "Unbenannter Napf";

    private final AdminService admins;
    private final NodeRepository nodes;
    private final FeedingSiteRepository sites;
    private final NodeDeploymentRepository deployments;
    private final AdminNodeAssignmentService assignments;

    public AdminNodesController(AdminService admins, NodeRepository nodes,
            FeedingSiteRepository sites, NodeDeploymentRepository deployments,
            AdminNodeAssignmentService assignments) {
        this.admins = admins;
        this.nodes = nodes;
        this.sites = sites;
        this.deployments = deployments;
        this.assignments = assignments;
    }

    public record NodeRow(UUID nodeId, String displayName, String displayOrFallback,
            UUID currentSiteId, String currentSiteName, String firmwareVersion,
            String protocolVersion, String statusNote, String lastContactAt) {}

    private static final DateTimeFormatter HISTORY_TIME = new java.time.format.DateTimeFormatterBuilder()
            .appendPattern("dd.MM.uuuu HH:mm:ss")
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(java.util.Locale.GERMAN).withZone(ZoneOffset.UTC);

    public record DeploymentRow(UUID id, UUID siteId, String siteName,
            String validFrom, String validUntil, String fromLabel, String untilLabel) {}

    public record SiteOption(UUID id, String name) {}

    // --- List ---

    @GetMapping({"", "/"})
    @Transactional(readOnly = true)
    public String list(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        UUID orgId = ctx.org().id();
        List<NodeDevice> own = nodes.findByOrganizationId(orgId);
        List<NodeDeployment> allDeployments = deployments.findByOrganizationId(orgId);
        Map<UUID, String> siteNames = new HashMap<>();
        for (FeedingSite s : sites.findByOrganizationId(orgId)) {
            siteNames.put(s.getId(), s.getName());
        }
        Instant now = Instant.now();
        Map<UUID, NodeDeployment> currentByNode = new HashMap<>();
        for (NodeDeployment d : allDeployments) {
            if (!covers(d, now)) {
                continue;
            }
            UUID nodeId = d.getNode().getNodeId();
            NodeDeployment prev = currentByNode.get(nodeId);
            if (prev == null || d.getValidFrom().isAfter(prev.getValidFrom())) {
                currentByNode.put(nodeId, d);
            }
        }
        List<NodeRow> rows = new ArrayList<>();
        for (NodeDevice n : own) {
            NodeDeployment current = currentByNode.get(n.getNodeId());
            UUID siteId = current == null ? null : current.getFeedingSite().getId();
            String siteName = null;
            if (current != null) {
                siteName = siteNames.get(siteId);
                if (siteName == null) {
                    siteName = current.getFeedingSite().getName();
                }
            }
            rows.add(new NodeRow(n.getNodeId(), n.getDisplayName(),
                    displayOrFallback(n.getDisplayName()),
                    siteId, siteName, n.getFirmwareVersion(),
                    n.getProtocolVersion(), n.getStatusNote(),
                    n.getLastContactAt() == null ? null
                            : n.getLastContactAt().toString()));
        }
        rows.sort(Comparator.comparing(
                (NodeRow r) -> r.displayName() == null ? null : r.displayName().toLowerCase(java.util.Locale.ROOT),
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.displayOrFallback().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(NodeRow::nodeId));
        model.addAttribute("nodes", rows);
        model.addAttribute("nodeCount", rows.size());
        return "admin/nodes-list";
    }

    // --- Detail ---

    @GetMapping("/{nodeId}")
    @Transactional(readOnly = true)
    public String detail(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @PathVariable UUID nodeId) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        NodeDevice node = loadOwn(ctx, nodeId);
        fillDetail(ctx, node, model);
        return "admin/nodes-detail";
    }

    // --- Rename ---

    @PostMapping("/{nodeId}/name")
    @Transactional
    public String rename(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model,
            @PathVariable UUID nodeId,
            @RequestParam UUID formOrganizationId,
            @RequestParam(value = "displayName", required = false) String displayName,
            RedirectAttributes redirect) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) {
            return "redirect:/admin/org";
        }
        if (!ctx.org().id().equals(formOrganizationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Organisation gewechselt. Bitte das Formular neu öffnen.");
        }
        nodes.findByIdLocked(nodeId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        NodeDevice node = loadOwn(ctx, nodeId);
        if (displayName == null) {
            fillDetail(ctx, node, model);
            model.addAttribute("errors", List.of("Bitte einen Napf-Namen eingeben."));
            model.addAttribute("formDisplayName", "");
            return "admin/nodes-detail";
        }
        String normalized = NodeDevice.normalizeDisplayName(displayName);
        if (normalized != null
                && normalized.length() > NodeDevice.MAX_DISPLAY_NAME_LENGTH) {
            fillDetail(ctx, node, model);
            model.addAttribute("errors", List.of(
                    "Napf-Name darf höchstens "
                            + NodeDevice.MAX_DISPLAY_NAME_LENGTH
                            + " Zeichen haben."));
            model.addAttribute("formDisplayName", displayName);
            return "admin/nodes-detail";
        }
        // Only the human-readable label changes. UUID, claiming state,
        // device keys and BLE identity are never touched here.
        node.setDisplayName(normalized);
        nodes.save(node);
        redirect.addFlashAttribute("success", "Napf-Name gespeichert.");
        return "redirect:/admin/nodes/" + node.getNodeId();
    }

    // --- Initial assignment / move ---

    @PostMapping("/{nodeId}/assignment")
    public String assign(@AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model, @PathVariable UUID nodeId,
            @RequestParam UUID formOrganizationId,
            @RequestParam String expectedDeploymentId,
            @RequestParam(value = "feedingSiteId", required = false) UUID feedingSiteId,
            RedirectAttributes redirect) {
        ActiveContext ctx = requireContext(principal, session, model);
        if (ctx == null) return "redirect:/admin/org";
        if (!ctx.org().id().equals(formOrganizationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Organisation gewechselt. Bitte das Formular neu öffnen.");
        }
        try {
            assignments.assign(principal.getId(), ctx.org().id(), nodeId,
                    feedingSiteId, expectedDeploymentId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT
                    && e.getStatusCode() != HttpStatus.BAD_REQUEST) throw e;
            // The service transaction has rolled back before feedback is rendered.
            redirect.addFlashAttribute("errors", List.of(friendlyMoveError(e)));
            return "redirect:/admin/nodes/" + nodeId;
        }
        redirect.addFlashAttribute("success", "Napf-Zuordnung gespeichert.");
        return "redirect:/admin/nodes/" + nodeId;
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

    private NodeDevice loadOwn(ActiveContext ctx, UUID nodeId) {
        // Tenant-scoped read: foreign ids are indistinguishable from absent
        // ones (404, no display name, site, or history leak). Only CLAIMED
        // nodes owned by the active org are manageable here.
        NodeDevice node = nodes.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null
                || !node.getOrganization().getId().equals(ctx.org().id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return node;
    }

    private void fillDetail(ActiveContext ctx, NodeDevice node, Model model) {
        UUID orgId = ctx.org().id();
        List<NodeDeployment> history =
                deployments.findByOrganizationIdAndNodeNodeId(orgId, node.getNodeId());
        history.sort(Comparator.comparing(NodeDeployment::getValidFrom).reversed());
        Instant now = Instant.now();
        NodeDeployment current = null;
        for (NodeDeployment d : history) {
            if (covers(d, now)
                    && (current == null
                            || d.getValidFrom().isAfter(current.getValidFrom()))) {
                current = d;
            }
        }
        List<DeploymentRow> rows = new ArrayList<>();
        for (NodeDeployment d : history) {
            rows.add(new DeploymentRow(d.getId(), d.getFeedingSite().getId(),
                    d.getFeedingSite().getName(),
                    d.getValidFrom().toString(),
                    d.getValidUntil() == null ? null : d.getValidUntil().toString(),
                    HISTORY_TIME.format(d.getValidFrom()),
                    d.getValidUntil() == null ? null : HISTORY_TIME.format(d.getValidUntil())));
        }
        List<FeedingSite> allSites = sites.findByOrganizationId(orgId);
        allSites.sort(Comparator.comparing(FeedingSite::getName,
                String.CASE_INSENSITIVE_ORDER));
        List<SiteOption> options = new ArrayList<>();
        for (FeedingSite s : allSites) {
            options.add(new SiteOption(s.getId(), s.getName()));
        }
        model.addAttribute("nodeId", node.getNodeId());
        model.addAttribute("displayName", node.getDisplayName());
        model.addAttribute("displayOrFallback",
                displayOrFallback(node.getDisplayName()));
        model.addAttribute("firmwareVersion", node.getFirmwareVersion());
        model.addAttribute("protocolVersion", node.getProtocolVersion());
        model.addAttribute("statusNote", node.getStatusNote());
        model.addAttribute("lastContactAt", node.getLastContactAt() == null ? null
                : node.getLastContactAt().toString());
        model.addAttribute("claimedAt",
                node.getClaimedAt() == null ? null : node.getClaimedAt().toString());
        model.addAttribute("nodeState", node.getState().name());
        model.addAttribute("currentSiteId",
                current == null ? null : current.getFeedingSite().getId());
        model.addAttribute("currentSiteName",
                current == null ? null : current.getFeedingSite().getName());
        model.addAttribute("expectedDeploymentId", history.stream()
                .filter(d -> d.getValidUntil() == null)
                .map(d -> d.getId().toString()).findFirst().orElse(""));
        model.addAttribute("deployments", rows);
        model.addAttribute("deploymentCount", rows.size());
        model.addAttribute("sites", options);
        model.addAttribute("siteCount", options.size());
        if (!model.containsAttribute("formDisplayName")) {
            model.addAttribute("formDisplayName",
                    node.getDisplayName() == null ? "" : node.getDisplayName());
        }
    }

    static boolean covers(NodeDeployment d, Instant now) {
        if (d.getValidFrom() != null && now.isBefore(d.getValidFrom())) {
            return false;
        }
        return d.getValidUntil() == null || now.isBefore(d.getValidUntil());
    }

    static String displayOrFallback(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return UNNAMED_FALLBACK;
    }

    private static String friendlyMoveError(ResponseStatusException e) {
        String reason = e.getReason() == null ? "" : e.getReason();
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        if (e.getStatusCode() == HttpStatus.CONFLICT
                && lower.contains("already assigned")) {
            return "Napf ist dieser Futterstelle bereits zugeordnet.";
        }
        if (e.getStatusCode() == HttpStatus.CONFLICT) {
            return "Die Zuordnung wurde geändert oder steht im Konflikt mit der Historie. Bitte aktuelle Zuordnung prüfen und erneut versuchen.";
        }
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
            return "Bitte eine gültige Futterstelle auswählen.";
        }
        return "Zuordnung nicht möglich. Bitte erneut versuchen.";
    }
}
