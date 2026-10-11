package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.CatPhotoService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization-local cat profile photos (#95). All reads require ACTIVE
 * membership of the owning organization; SYSADMIN alone grants no access.
 * Bytes are served only here, never as public static resources.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/cats/{catId}/photo")
public class CatPhotoController {
    private final CatPhotoService photos;

    public CatPhotoController(CatPhotoService photos) {
        this.photos = photos;
    }

    @Schema(name = "CatPhotoMeta")
    public record PhotoMetaView(boolean hasPhoto, String updatedAt, int width,
            int height, int byteSize) {}

    private static PhotoMetaView toView(CatPhotoService.PhotoMeta meta) {
        return new PhotoMetaView(meta.hasPhoto(), meta.updatedAt(),
                meta.width(), meta.height(), meta.byteSize());
    }

    @GetMapping(produces = "image/jpeg")
    @Operation(operationId = "getCatPhoto",
            summary = "Read the organization's own cat photo (requires ACTIVE membership)")
    public ResponseEntity<byte[]> get(@PathVariable UUID organizationId,
            @PathVariable UUID catId,
            @AuthenticationPrincipal AppUserDetails principal,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "If-None-Match", required = false) String ifNoneMatch) {
        CatPhotoService.PhotoData data = photos.readOwn(
                TenantService.currentUserId(principal), organizationId, catId);
        if (data.etag() != null && data.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(data.etag())
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return image(data);
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json")
    @Operation(operationId = "putCatPhoto",
            summary = "Upload or replace the organization's own cat photo")
    public PhotoMetaView put(@PathVariable UUID organizationId,
            @PathVariable UUID catId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "photo file is required");
        }
        byte[] upload;
        try {
            upload = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "photo file is required");
        }
        // The uploaded filename and declared content type are never trusted
        // or retained; validation uses magic bytes and decoded content only.
        return toView(photos.store(TenantService.currentUserId(principal),
                organizationId, catId, upload));
    }

    @DeleteMapping
    @Operation(operationId = "deleteCatPhoto",
            summary = "Remove the organization's own cat photo")
    public ResponseEntity<Void> delete(@PathVariable UUID organizationId,
            @PathVariable UUID catId,
            @AuthenticationPrincipal AppUserDetails principal) {
        photos.delete(TenantService.currentUserId(principal), organizationId,
                catId);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static ResponseEntity<byte[]> image(CatPhotoService.PhotoData data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(data.contentType()));
        headers.setContentLength(data.bytes().length);
        headers.setETag(data.etag());
        // Tenant-private bytes must never sit in a shared or local HTTP
        // cache: no-store keeps every read server-authorized, so logout,
        // organization switch and revocation take effect immediately. The
        // ETag still serves explicit conditional API clients.
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'");
        headers.setContentDispositionFormData("inline", "cat-photo.jpg");
        return new ResponseEntity<>(data.bytes(), headers, HttpStatus.OK);
    }
}
