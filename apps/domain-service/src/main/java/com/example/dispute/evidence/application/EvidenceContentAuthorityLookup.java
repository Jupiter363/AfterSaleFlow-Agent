package com.example.dispute.evidence.application;

import java.util.Optional;

/** Read port used to re-bind a frozen Java Evidence request to persisted parser authority. */
public interface EvidenceContentAuthorityLookup {
    Optional<StoredAuthority> findExact(
            String caseId,
            String evidenceId,
            String fileSha256,
            String contentType,
            long fileSize,
            String parserVersion);

    /**
     * The byte size stays in the Java persistence authority rather than the cross-language payload.
     * It lets the invocation producer re-check the stored-object coordinate without adding a field the
     * strict Python envelope does not accept.
     */
    record StoredAuthority(EvidenceContentAuthorityV1 authority, long fileSize) {
        public StoredAuthority {
            if (authority == null || fileSize < 1 || fileSize > 25L * 1024 * 1024) {
                throw new IllegalArgumentException("stored evidence content authority is invalid");
            }
        }
    }
}
