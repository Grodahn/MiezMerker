package org.miezmerker.backend.service;

import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared #9/#11 cat maintenance; callers retain their stricter route authorization. */
@Service
public class CatService {
    private final CatRepository cats;
    private final OrganizationRepository organizations;
    private final TenantService tenants;
    public CatService(CatRepository cats, OrganizationRepository organizations, TenantService tenants) {
        this.cats = cats; this.organizations = organizations; this.tenants = tenants;
    }
    @Transactional
    public Cat create(UUID user, UUID org, String chipId, String name, String status, String notes) {
        tenants.requireActive(user, org);
        var organization = organizations.findById(org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String chip = validChip(chipId);
        checkDuplicate(org, chip);
        return save(new Cat(organization, chip, blankToNull(name), blankToNull(status), blankToNull(notes)));
    }
    @Transactional
    public Cat update(UUID user, UUID org, UUID id, String chipId, String name, String status, String notes) {
        tenants.requireActive(user, org);
        Cat cat = cats.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (chipId != null) {
            String chip = validChip(chipId);
            if (!chip.equals(cat.getChipId())) checkDuplicate(org, chip);
            cat.setChipId(chip);
        }
        if (name != null) cat.setName(blankToNull(name));
        if (status != null) cat.setStatus(blankToNull(status));
        if (notes != null) cat.setNotes(blankToNull(notes));
        return save(cat);
    }
    private String validChip(String value) {
        String chip = Cat.normalizeChipId(value);
        if (chip == null || chip.isEmpty() || chip.length() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chipId must have 1..64 characters");
        return chip;
    }
    private void checkDuplicate(UUID org, String chip) {
        if (cats.findByOrganizationIdAndChipId(org, chip).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "chipId already registered in this organization");
    }
    private Cat save(Cat cat) {
        try { return cats.saveAndFlush(cat); }
        catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "chipId already registered in this organization");
        }
    }
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
