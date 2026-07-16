package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingFlowActionRepository
        extends JpaRepository<HearingFlowActionEntity, String> {
    Optional<HearingFlowActionEntity> findByStageIdAndActionType(
            String stageId, HearingFlowActionType actionType);

    Optional<HearingFlowActionEntity> findByStageIdAndActionTypeAndParticipantId(
            String stageId, HearingFlowActionType actionType, String participantId);

    Optional<HearingFlowActionEntity> findByFlowInstanceIdAndActionType(
            String flowInstanceId, HearingFlowActionType actionType);

    Optional<HearingFlowActionEntity> findByFlowInstanceIdAndActionTypeAndParticipantId(
            String flowInstanceId,
            HearingFlowActionType actionType,
            String participantId);

    List<HearingFlowActionEntity> findAllByFlowInstanceIdOrderByCreatedAtAsc(
            String flowInstanceId);

    List<HearingFlowActionEntity> findAllByStageIdOrderByCreatedAtAsc(String stageId);
}
