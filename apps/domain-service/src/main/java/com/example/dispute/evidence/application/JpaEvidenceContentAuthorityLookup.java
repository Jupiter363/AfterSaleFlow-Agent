package com.example.dispute.evidence.application;

import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceContentAuthorityRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Repository-backed lookup that rejects a result bound to any different file coordinate. */
@Component
public final class JpaEvidenceContentAuthorityLookup implements EvidenceContentAuthorityLookup {
    private final EvidenceContentAuthorityRepository repository;

    public JpaEvidenceContentAuthorityLookup(EvidenceContentAuthorityRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StoredAuthority> findExact(
            String caseId,
            String evidenceId,
            String fileSha256,
            String contentType,
            long fileSize,
            String parserVersion) {
        return repository
                .findByEvidenceIdAndFileSha256AndParserVersion(
                        evidenceId, fileSha256, parserVersion)
                .filter(entity -> caseId.equals(entity.getCaseId()))
                .filter(entity -> contentType.equals(entity.getContentType()))
                .filter(entity -> fileSize == entity.getFileSize())
                .map(entity -> new StoredAuthority(entity.authority(), entity.getFileSize()));
    }
}
