package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakePartyCompletionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseIntakePartyCompletionRepository
        extends JpaRepository<CaseIntakePartyCompletionEntity, String> {

    Optional<CaseIntakePartyCompletionEntity>
            findByCaseIdAndParticipantRoleAndParticipantId(
                    String caseId, ActorRole participantRole, String participantId);

    List<CaseIntakePartyCompletionEntity> findAllByCaseId(String caseId);

    long countByCaseId(String caseId);
}
