package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceContentAuthorityEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceContentAuthorityRepository
        extends JpaRepository<EvidenceContentAuthorityEntity, String> {
    Optional<EvidenceContentAuthorityEntity> findByEvidenceIdAndFileSha256AndParserVersion(
            String evidenceId, String fileSha256, String parserVersion);

    Optional<EvidenceContentAuthorityEntity> findByParseOutboxId(String parseOutboxId);
}
