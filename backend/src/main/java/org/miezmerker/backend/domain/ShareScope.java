package org.miezmerker.backend.domain;

/**
 * Outgoing read-only sharing scopes (ADR 0016). Scope prerequisites are enforced
 * by the grant-resolution layer: VISITS requires CARE, SITE_LABEL requires
 * CARE + VISITS, PHOTO requires CARE plus a distinct media authorization check.
 */
public enum ShareScope {
    CARE,
    VISITS,
    SITE_LABEL,
    PHOTO
}
