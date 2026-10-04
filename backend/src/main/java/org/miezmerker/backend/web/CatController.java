package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization-scoped cats (#9). The chip id is unique per organization and
 * normalized (trimmed, uppercased); the same chip id may exist independently
 * in two different organizations without conferring cross-tenant access.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/cats")
public class CatController {
    private final CatRepository cats;
    private final OrganizationRepository organizations;
    private final TenantService tenants;

    public CatController(CatRepository cats, OrganizationRepository organizations,
            TenantService tenants) {
        this.cats = cats;
        this.organizations = organizations;
        this.tenants = tenants;
    }

    @Schema(name = "CatView")
    public record CatView(UUID id, String organizationId, String chipId, String name,
            String status, String notes, String createdAt, String updatedAt) {}

    @Schema(name = "CreateCatRequest")
    public record CreateCatRequest(
            @NotBlank @Size(max = 64) String chipId,
            @Size(max = 255) String name,
            @Size(max = 64) String status,
            @Size(max = 2000) String notes) {}

    @Schema(name = "UpdateCatRequest")
    public record UpdateCatRequest(
            @Size(max = 64) String chipId,
            @Size(max = 255) String name,
            @Size(max = 64) String status,
            @Size(max = 2000) String notes) {}

    private static CatView toView(Cat c) {
        return new CatView(c.getId(), c.getOrganization().getId().toString(), c.getChipId(),
                c.getName(), c.getStatus(), c.getNotes(), c.getCreatedAt().toString(),
                c.getUpdatedAt().toString());
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listCats",
            summary = "List cats of an organization (requires ACTIVE membership)")
    @Transactional(readOnly = true)
    public List<CatView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        return cats.findByOrganizationId(organizationId).stream()
                .map(CatController::toView).toList();
    }

    @GetMapping(value = "/{catId}", produces = "application/json")
    @Operation(operationId = "getCat",
            summary = "Cat details; never readable across organizations")
    @Transactional(readOnly = true)
    public CatView get(@PathVariable UUID organizationId, @PathVariable UUID catId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        return toView(cats.findByIdAndOrganizationId(catId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "createCat",
            summary = "ADMIN creates a cat in their own organization")
    @Transactional
    public CatView create(@PathVariable UUID organizationId,
            @Valid @RequestBody CreateCatRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String chip = Cat.normalizeChipId(request.chipId());
        if (chip == null || chip.isEmpty() || chip.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "chipId must have 1..64 characters");
        }
        if (cats.findByOrganizationIdAndChipId(organizationId, chip).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "chipId already registered in this organization");
        }
        Cat cat = new Cat(org, chip, blankToNull(request.name()),
                blankToNull(request.status()), blankToNull(request.notes()));
        try {
            return toView(cats.saveAndFlush(cat));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "chipId already registered in this organization");
        }
    }

    @PatchMapping(value = "/{catId}", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "updateCat",
            summary = "ADMIN updates a cat of their own organization")
    @Transactional
    public CatView update(@PathVariable UUID organizationId, @PathVariable UUID catId,
            @Valid @RequestBody UpdateCatRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        Cat cat = cats.findByIdAndOrganizationId(catId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (request.chipId() != null) {
            String chip = Cat.normalizeChipId(request.chipId());
            if (chip == null || chip.isEmpty() || chip.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "chipId must have 1..64 characters");
            }
            if (!chip.equals(cat.getChipId())
                    && cats.findByOrganizationIdAndChipId(organizationId, chip).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "chipId already registered in this organization");
            }
            cat.setChipId(chip);
        }
        if (request.name() != null) {
            cat.setName(blankToNull(request.name()));
        }
        if (request.status() != null) {
            cat.setStatus(blankToNull(request.status()));
        }
        if (request.notes() != null) {
            cat.setNotes(blankToNull(request.notes()));
        }
        try {
            return toView(cats.saveAndFlush(cat));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "chipId already registered in this organization");
        }
    }

    @DeleteMapping("/{catId}")
    @Operation(operationId = "deleteCat",
            summary = "ADMIN deletes a cat of their own organization")
    @Transactional
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID catId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        Cat cat = cats.findByIdAndOrganizationId(catId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        cats.delete(cat);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
