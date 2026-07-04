package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseParticipantRepository
        extends JpaRepository<CaseParticipantEntity, String> {

    List<CaseParticipantEntity> findAllByCaseId(String caseId);

    boolean existsByCaseIdAndActorIdAndParticipantRole(
            String caseId, String actorId, ActorRole participantRole);

    Optional<CaseParticipantEntity>
            findByCaseIdAndActorIdAndParticipantRole(
                    String caseId,
                    String actorId,
                    ActorRole participantRole);
}
