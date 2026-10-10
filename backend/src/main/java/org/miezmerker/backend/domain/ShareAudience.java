package org.miezmerker.backend.domain;

/**
 * Audience of one outgoing sharing policy (ADR 0016). PRIVATE is the default
 * and shares with nobody; ALL_DISCOVERABLE covers any other ACTIVE and
 * discoverable organization on this instance; ALLOWLIST names explicit
 * recipient organizations.
 */
public enum ShareAudience {
    PRIVATE,
    ALL_DISCOVERABLE,
    ALLOWLIST
}
