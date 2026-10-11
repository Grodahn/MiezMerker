package org.miezmerker.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.CatPhoto;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.repo.CatPhotoRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization-local cat profile photos (#95, ADR 0016).
 *
 * <p>Photos belong to an organization-local {@link Cat}, never to a global
 * chip identity: the same chip in two organizations keeps two independent
 * photos, and replacing one never touches the other. All reads go through
 * {@link TenantService} membership authorization; SYSADMIN alone grants no
 * access to private media.
 *
 * <p>Files live as random, non-guessable names in a configurable persistent
 * directory and are served only through authorization-checked endpoints.
 * Uploaded filenames are never used as storage paths.
 */
@Service
public class CatPhotoService {
    private static final Logger log = LoggerFactory.getLogger(CatPhotoService.class);

    @PersistenceContext
    private EntityManager entities;

    private final CatRepository cats;
    private final CatPhotoRepository photos;
    private final OrganizationRepository organizations;
    private final TenantService tenants;

    private final Path storageDir;
    private final long maxUploadBytes;
    private final int maxDimension;
    private final long maxPixels;
    private final int targetLongEdge;
    private final float jpegQuality;

    /**
     * Fixed-size JVM-local lock stripes serializing concurrent replace/delete
     * of the same logical photo. The database row lock alone cannot serialize
     * two concurrent inserts (no row exists yet); without this, one of them
     * would fail with a constraint violation. Bounded (64 stripes) so lock
     * state cannot grow with data. Multi-instance deployments keep the
     * database constraint as the final guard and translate the residual race
     * into a retryable 409 (see persistLocked).
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] photoLocks = new Object[LOCK_STRIPES];

    private Object lockFor(UUID catId) {
        return photoLocks[(catId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    public CatPhotoService(CatRepository cats, CatPhotoRepository photos,
            OrganizationRepository organizations, TenantService tenants,
            @Value("${miezmerker.media.storage-dir:data/cat-photos}") String storageDir,
            @Value("${miezmerker.media.max-upload-bytes:5242880}") long maxUploadBytes,
            @Value("${miezmerker.media.max-dimension:4096}") int maxDimension,
            @Value("${miezmerker.media.max-pixels:12000000}") long maxPixels,
            @Value("${miezmerker.media.target-long-edge:1600}") int targetLongEdge,
            @Value("${miezmerker.media.jpeg-quality:0.85}") float jpegQuality) {
        this.cats = cats;
        this.photos = photos;
        this.organizations = organizations;
        this.tenants = tenants;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            photoLocks[i] = new Object();
        }
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.targetLongEdge = targetLongEdge;
        this.jpegQuality = jpegQuality;
    }

    /** Public metadata for cat views; never exposes storage internals. */
    public record PhotoMeta(boolean hasPhoto, String updatedAt, int width, int height,
            int byteSize) {}

    /** Authorized image bytes with their content type and version tag. */
    public record PhotoData(byte[] bytes, String contentType, String etag) {}

    @Transactional(readOnly = true)
    public PhotoMeta info(UUID user, UUID org, UUID catId) {
        tenants.requireActive(user, org);
        return photos.findByCatIdAndOrganizationId(catId, org)
                .map(CatPhotoService::toMeta)
                .orElse(new PhotoMeta(false, null, 0, 0, 0));
    }

    @Transactional(readOnly = true)
    public Map<UUID, PhotoMeta> presence(UUID user, UUID org, List<UUID> catIds) {
        tenants.requireActive(user, org);
        if (catIds == null || catIds.isEmpty()) {
            return Map.of();
        }
        // One tenant-scoped query for the whole list; photo counts stay
        // bounded by the organization's own cats.
        Set<UUID> wanted = Set.copyOf(catIds);
        java.util.Map<UUID, PhotoMeta> result = new java.util.HashMap<>();
        for (CatPhoto photo : photos.findByOrganizationIdAndCatIdIn(org, wanted)) {
            result.put(photo.getCatId(), toMeta(photo));
        }
        return result;
    }

    /** Own-organization read: ACTIVE membership of the owning organization. */
    @Transactional(readOnly = true)
    public PhotoData readOwn(UUID user, UUID org, UUID catId) {
        tenants.requireActive(user, org);
        CatPhoto photo = photos.findByCatIdAndOrganizationId(catId, org)
                .orElseThrow(CatPhotoService::hidden);
        return readFile(photo);
    }

    /**
     * Observation-backed foreign read for a server-resolved chip. The caller
     * ({@link SharedCareService}) has already verified ACTIVE membership,
     * rate limits and genuine observation proof; this method only applies the
     * owner-side grant check (effective CARE plus PHOTO, both organizations
     * ACTIVE and discoverable) and resolves the owner's photo for that chip.
     * The chip is resolved server-side from the observation; caller-supplied
     * chip ids or foreign cat ids never authorize access. CARE alone,
     * hidden/disabled organizations and revoked grants all deny identically.
     */
    @Transactional(readOnly = true)
    public PhotoData readSharedForChip(UUID recipientOrg, UUID sourceOrg,
            String chip) {
        List<CatPhoto> rows = entities.createQuery(
                "select p from CatPhoto p join p.cat c join c.organization owner "
                        + "where owner.id = :owner and c.chipId = :chip and "
                        + SharePolicyService.effectiveScopePredicate("owner",
                                org.miezmerker.backend.domain.ShareScope.PHOTO),
                CatPhoto.class)
                .setParameter("recipient", recipientOrg)
                .setParameter("owner", sourceOrg)
                .setParameter("chip", chip)
                .setMaxResults(2)
                .getResultList();
        if (rows.size() != 1) {
            throw hidden();
        }
        return readFile(rows.get(0));
    }

    /**
     * Upload or replace the organization's own cat photo. Any ACTIVE member
     * may edit (same authority as cat updates); cross-organization edits are
     * impossible because the cat lookup is tenant-scoped. A failed upload
     * never touches the current photo: validation and normalization happen
     * before any metadata or file mutation.
     */
    @Transactional
    public PhotoMeta store(UUID user, UUID org, UUID catId, byte[] upload) {
        tenants.requireActive(user, org);
        Cat cat = cats.findByIdAndOrganizationId(catId, org)
                .orElseThrow(CatPhotoService::hidden);
        Organization organization = organizations.findById(org)
                .orElseThrow(CatPhotoService::hidden);
        CatPhotoImage.Normalized normalized = CatPhotoImage.normalize(upload,
                maxUploadBytes, maxDimension, maxPixels, targetLongEdge,
                jpegQuality);
        synchronized (lockFor(catId)) {
            Path staged = stage(normalized.bytes());
            try {
                persistLocked(cat, organization, normalized, staged);
            } catch (RuntimeException e) {
                deleteQuietly(staged);
                throw e;
            }
            // The staged file is moved into place by persistLocked; nothing left.
            if (Files.exists(staged)) {
                deleteQuietly(staged);
                log.warn("cat photo stage file survived replacement for cat {}", catId);
            }
            return new PhotoMeta(true,
                    photos.findByCatIdAndOrganizationId(catId, org).map(
                            photo -> photo.getUpdatedAt().toString()).orElse(null),
                    normalized.width(), normalized.height(), normalized.bytes().length);
        }
    }

    /**
     * Removes the organization's own photo. File deletion happens after the
     * transaction commits so a rollback never leaves metadata without bytes;
     * a failed file deletion is logged for operator cleanup, never surfaced
     * with internal paths.
     */
    @Transactional
    public void delete(UUID user, UUID org, UUID catId) {
        tenants.requireActive(user, org);
        // Tenant-scoped: a cat of another organization is simply not found.
        cats.findByIdAndOrganizationId(catId, org)
                .orElseThrow(CatPhotoService::hidden);
        synchronized (lockFor(catId)) {
            CatPhoto photo = photos.lockByCatIdAndOrganizationId(catId, org)
                    .orElseThrow(CatPhotoService::hidden);
            Path file = pathFor(photo.getStorageKey());
            photos.delete(photo);
            photos.flush();
            afterCommit(() -> deleteQuietly(file));
        }
    }

    /**
     * Best-effort file cleanup when a cat itself is deleted. Tenant-scoped
     * like every other mutation: the caller proves ownership with the
     * preceding cat lookup, and the lock/delete below re-checks it.
     */
    @Transactional
    public void deleteForCatRemoval(UUID user, UUID org, UUID catId) {
        tenants.requireActive(user, org);
        cats.findByIdAndOrganizationId(catId, org)
                .orElseThrow(CatPhotoService::hidden);
        synchronized (lockFor(catId)) {
            photos.lockByCatIdAndOrganizationId(catId, org).ifPresent(photo -> {
                Path file = pathFor(photo.getStorageKey());
                photos.delete(photo);
                photos.flush();
                afterCommit(() -> deleteQuietly(file));
            });
        }
    }

    private String persistLocked(Cat cat, Organization organization,
            CatPhotoImage.Normalized normalized, Path staged) {
        ensureStorageDir();
        String key = UUID.randomUUID().toString().replace("-", "")
                + CatPhotoImage.OUTPUT_EXTENSION;
        Path target = pathFor(key);
        if (!target.getParent().equals(storageDir)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
        moveIntoPlace(staged, target);
        // If the surrounding transaction rolls back after the bytes moved
        // (e.g. a late commit conflict in an outer admin transaction), the
        // unreferenced bytes are removed again: no orphaned files, no
        // metadata without bytes.
        onRollback(() -> deleteQuietly(target));
        final String[] previous = new String[1];
        try {
            CatPhoto locked = photos
                    .lockByCatIdAndOrganizationId(cat.getId(), organization.getId())
                    .orElse(null);
            if (locked == null) {
                photos.saveAndFlush(new CatPhoto(cat, organization, key,
                        CatPhotoImage.OUTPUT_CONTENT_TYPE,
                        normalized.bytes().length, normalized.width(),
                        normalized.height()));
            } else {
                previous[0] = locked.getStorageKey();
                locked.replace(key, CatPhotoImage.OUTPUT_CONTENT_TYPE,
                        normalized.bytes().length, normalized.width(),
                        normalized.height());
                photos.saveAndFlush(locked);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Residual insert race (e.g. multi-instance): the logical photo
            // already exists. Report a retryable conflict, never a 500, and
            // never leave the orphaned bytes behind.
            deleteQuietly(target);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "photo changed concurrently; please retry");
        } catch (RuntimeException e) {
            // The bytes are already in place; remove them so a failed
            // replacement never leaves orphaned files behind.
            deleteQuietly(target);
            throw e;
        }
        if (previous[0] != null) {
            Path oldFile = pathFor(previous[0]);
            afterCommit(() -> deleteQuietly(oldFile));
        }
        return key;
    }

    private PhotoData readFile(CatPhoto photo) {
        Path file = pathFor(photo.getStorageKey());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("cat photo bytes missing for cat {}", photo.getCatId());
            throw hidden();
        }
        if (bytes.length == 0) {
            throw hidden();
        }
        String etag = "\"" + Long.toHexString(photo.getUpdatedAt().toEpochMilli())
                + "-" + Integer.toHexString(bytes.length) + "\"";
        return new PhotoData(bytes, photo.getContentType(), etag);
    }

    private static PhotoMeta toMeta(CatPhoto photo) {
        return new PhotoMeta(true, photo.getUpdatedAt().toString(),
                photo.getWidth(), photo.getHeight(), photo.getByteSize());
    }

    /** Uniform denied/not-found: media existence is never enumerable. */
    private static ResponseStatusException hidden() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "photo not available in this organization");
    }

    private Path pathFor(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("/") || storageKey.contains("\\")
                || storageKey.contains("..")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
        return storageDir.resolve(storageKey).normalize();
    }

    private void ensureStorageDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
    }

    private Path stage(byte[] bytes) {
        ensureStorageDir();
        try {
            Path staged = Files.createTempFile(storageDir, "upload-", ".tmp");
            Files.write(staged, bytes);
            return staged;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
    }

    private static void moveIntoPlace(Path staged, Path target) {
        try {
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(staged);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
    }

    private static void afterCommit(Runnable action) {
        synchronize(TransactionSynchronization.STATUS_COMMITTED, action);
    }

    private static void onRollback(Runnable action) {
        synchronize(TransactionSynchronization.STATUS_ROLLED_BACK, action);
    }

    private static void synchronize(int status, Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int completion) {
                            if (completion == status) {
                                action.run();
                            }
                        }
                    });
        } else if (status == TransactionSynchronization.STATUS_COMMITTED) {
            action.run();
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("cat photo cleanup failed for {}", file.getFileName());
        }
    }
}
