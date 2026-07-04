package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingRoundPartySubmissionRepository
        extends JpaRepository<HearingRoundPartySubmissionEntity, String> {

    List<HearingRoundPartySubmissionEntity> findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
            String caseId, int roundNo);

    Optional<HearingRoundPartySubmissionEntity> findByCaseIdAndRoundNoAndParticipantRole(
            String caseId, int roundNo, ActorRole participantRole);
}
