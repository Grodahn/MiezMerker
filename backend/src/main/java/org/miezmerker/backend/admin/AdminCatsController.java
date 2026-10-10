package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.CatService;
import org.miezmerker.backend.service.CatHistoryService;
import org.miezmerker.backend.service.ChipActivityService;
import org.miezmerker.backend.web.CatController.CreateCatRequest;
import org.miezmerker.backend.web.CatController.UpdateCatRequest;
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
import org.springframework.web.util.UriComponentsBuilder;

/** #37: Admin context is tenant authority; no organization or chip identity edits. */
@Controller
@RequestMapping("/admin/cats")
@Transactional(readOnly = true)
public class AdminCatsController {
    private final AdminService admins;
    private final CatRepository cats;
    private final FeedingSiteRepository sites;
    private final CatService maintenance;
    private final ChipActivityService activity;
    private final CatHistoryService history;
    private final Validator validator;
    public AdminCatsController(AdminService admins, CatRepository cats, FeedingSiteRepository sites,
            CatService maintenance, ChipActivityService activity, CatHistoryService history, Validator validator) {
        this.admins = admins;
        this.cats = cats;
        this.sites = sites;
        this.maintenance = maintenance;
        this.activity = activity;
        this.history = history;
        this.validator = validator;
    }
    public record Site(UUID id, String name) {}
    public record Row(UUID id, String chipId, String name, String status, String notes,
            String sighting, String received, long count, long uncertain, List<Site> lastSites,
            List<Site> knownSites) {}
    private UUID context(AppUserDetails principal, HttpSession session, Model model) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var orgs = admins.adminOrgs(principal.getId());
        if (orgs.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var active = admins.currentOrgOrNull(principal, session);
        if (active == null) {
            if (orgs.size() > 1) return null;
            active = admins.selectOrg(principal.getId(), orgs.get(0).id(), session);
        }
        model.addAttribute("userLabel", admins.displayLabelFor(principal.getId()));
        model.addAttribute("userEmail", admins.emailFor(principal.getId()));
        model.addAttribute("activeOrg", active);
        model.addAttribute("adminOrgs", orgs);
        return active.id();
    }
    private Cat own(UUID org, UUID id) {
        return cats.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private List<Row> rows(UUID user, UUID org) {
        Map<String, Cat> known = new LinkedHashMap<>();
        cats.findByOrganizationId(org).stream().sorted(Comparator.comparing(Cat::getChipId))
                .forEach(c -> known.put(c.getChipId(), c));
        Map<String, ChipActivityService.Activity> summaries = new LinkedHashMap<>();
        activity.list(user, org).forEach(a -> summaries.put(a.chipId(), a));
        Map<String, Site> siteNames = new HashMap<>();
        sites.findByOrganizationId(org).forEach(s ->
                siteNames.put(s.getId().toString(), new Site(s.getId(), s.getName())));
        Map<String, List<Site>> last = new HashMap<>();
        history.lastSites(user, org).forEach(s ->
                last.computeIfAbsent(s.chipId(), k -> new ArrayList<>()).add(new Site(s.id(), s.name())));
        Set<String> chips = new LinkedHashSet<>(known.keySet());
        chips.addAll(summaries.keySet());
        return chips.stream().map(chip -> {
            Cat c = known.get(chip);
            var a = summaries.get(chip);
            return new Row(c == null ? null : c.getId(), chip, c == null ? null : c.getName(),
                    c == null ? null : c.getStatus(), c == null ? null : c.getNotes(),
                    a == null || a.lastSeenAtMillis() == null ? null : Instant.ofEpochMilli(a.lastSeenAtMillis()).toString(),
                    a == null ? null : a.lastReceivedAt(), a == null ? 0 : a.observationCount(),
                    a == null ? 0 : a.uncertainClockCount(), last.getOrDefault(chip, List.of()),
                    a == null ? List.of() : a.feedingSiteIds().stream().map(siteNames::get).filter(Objects::nonNull).toList());
        }).toList();
    }
    @GetMapping({"", "/"})
    public String list(@AuthenticationPrincipal AppUserDetails principal, HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        var all = rows(principal.getId(), org);
        model.addAttribute("cats", all.stream().filter(r -> r.id() != null).toList());
        model.addAttribute("unknown", all.stream().filter(r -> r.id() == null).toList());
        return "admin/cats-list";
    }
    @GetMapping("/{catId}")
    public String detail(@PathVariable UUID catId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "offset must be >= 0 and limit must be 1..100");
        }
        Cat cat = own(org, catId);
        model.addAttribute("cat", rows(principal.getId(), org).stream()
                .filter(r -> catId.equals(r.id())).findFirst().orElseThrow());
        var page = history.visitPage(principal.getId(), org, cat.getChipId(), offset, limit + 1);
        boolean more = page.size() > limit;
        model.addAttribute("visits", more ? page.subList(0, limit) : page);
        model.addAttribute("visitOffset", offset);
        model.addAttribute("visitLimit", limit);
        model.addAttribute("visitTotal", history.visitTotal(principal.getId(), org, cat.getChipId()));
        model.addAttribute("visitPrevious",
                offset > 0 ? detailUrl(catId, Math.max(0, offset - limit), limit) : null);
        model.addAttribute("visitNext", more ? detailUrl(catId, offset + limit, limit) : null);
        model.addAttribute("siteVisits", history.latestSiteVisits(principal.getId(), org, cat.getChipId()));
        return "admin/cats-detail";
    }
    @GetMapping("/{catId}/edit")
    public String edit(@PathVariable UUID catId, @AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        Cat cat = own(org, catId);
        form(model, catId, cat.getChipId(), cat.getName(), cat.getStatus(), cat.getNotes());
        return "admin/cats-form";
    }
    private void form(Model model, UUID id, String chip, String name, String status, String notes) {
        model.addAttribute("catId", id);
        model.addAttribute("chipId", chip);
        model.addAttribute("formName", name);
        model.addAttribute("formStatus", status);
        model.addAttribute("formNotes", notes);
        model.addAttribute("formAction", id == null ? "/admin/cats/from-chip" : "/admin/cats/" + id);
    }
    private String unknownChip(UUID user, UUID org, String raw) {
        String chip = Cat.normalizeChipId(raw);
        if (chip == null || cats.findByOrganizationIdAndChipId(org, chip).isPresent()
                || activity.list(user, org).stream().noneMatch(a -> a.chipId().equals(chip)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return chip;
    }
    @GetMapping("/from-chip")
    public String newForm(@RequestParam String chipId, @AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        form(model, null, unknownChip(principal.getId(), org, chipId), "", "", "");
        return "admin/cats-form";
    }
    @PostMapping("/{catId}")
    @Transactional
    public String update(@PathVariable UUID catId, @RequestParam UUID formOrganizationId,
            @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String notes, @AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        Cat cat = own(org, catId);
        stale(org, formOrganizationId);
        var errors = validator.validate(new UpdateCatRequest(null, name, status, notes));
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors.stream().map(e -> e.getPropertyPath() + ": " + e.getMessage()).toList());
            form(model, catId, cat.getChipId(), name, status, notes);
            return "admin/cats-form";
        }
        maintenance.update(principal.getId(), org, catId, null, name, status, notes);
        return "redirect:/admin/cats/" + catId;
    }
    @PostMapping("/from-chip")
    @Transactional
    public String create(@RequestParam UUID formOrganizationId, @RequestParam String chipId,
            @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String notes, @AuthenticationPrincipal AppUserDetails principal,
            HttpSession session, Model model) {
        UUID org = context(principal, session, model);
        if (org == null) return "redirect:/admin/org";
        stale(org, formOrganizationId);
        String chip = unknownChip(principal.getId(), org, chipId);
        var errors = validator.validate(new CreateCatRequest(chip, name, status, notes));
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors.stream().map(e -> e.getPropertyPath() + ": " + e.getMessage()).toList());
            form(model, null, chip, name, status, notes);
            return "admin/cats-form";
        }
        Cat cat = maintenance.create(principal.getId(), org, chip, name, status, notes);
        return "redirect:/admin/cats/" + cat.getId();
    }
    private void stale(UUID active, UUID submitted) {
        if (!active.equals(submitted)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Organisation gewechselt. Bitte Formular neu öffnen.");
    }
    private String detailUrl(UUID catId, int offset, int limit) {
        return UriComponentsBuilder.fromPath("/admin/cats/{catId}")
                .queryParam("offset", offset).queryParam("limit", limit)
                .buildAndExpand(catId).encode().toUriString();
    }
}
