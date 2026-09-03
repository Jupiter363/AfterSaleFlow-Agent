package com.example.dispute.evidence.application;

/**
 * Stable fail-closed boundary for a supported current text attachment that has not yet acquired
 * its exact immutable parsed-content authority.
 */
public final class EvidenceContentAuthorityUnavailableException extends IllegalStateException {
    public static final String CODE = "EVIDENCE_CONTENT_AUTHORITY_NOT_READY";

    public EvidenceContentAuthorityUnavailableException() {
        super("supported text evidence content authority is not ready");
    }

    public String code() {
        return CODE;
    }
}
