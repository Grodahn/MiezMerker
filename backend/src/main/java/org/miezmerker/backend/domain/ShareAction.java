package org.miezmerker.backend.domain;

/**
 * Audit actions of the append-only sharing ledger (ADR 0016): explicit grants
 * and revocations, plus visibility/status-driven invalidation.
 */
public enum ShareAction {
    GRANT,
    REVOKE,
    INVALIDATE
}
