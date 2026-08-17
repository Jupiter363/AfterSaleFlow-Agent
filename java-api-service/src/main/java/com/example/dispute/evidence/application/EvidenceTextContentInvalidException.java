package com.example.dispute.evidence.application;

/** Permanent rejection for bytes that cannot be the exact admitted supported text source. */
public final class EvidenceTextContentInvalidException extends IllegalStateException {
    public EvidenceTextContentInvalidException(String message) {
        super(message);
    }

    public EvidenceTextContentInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
