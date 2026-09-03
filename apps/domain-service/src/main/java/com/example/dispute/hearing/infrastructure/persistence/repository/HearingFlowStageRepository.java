package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingFlowStageRepository
        extends JpaRepository<HearingFlowStageEntity, String> {
    Optional<HearingFlowStageEntity> findByFlowInstanceIdAndStageCode(
            String flowInstanceId, HearingFlowStage stageCode);

    Optional<HearingFlowStageEntity> findByFlowInstanceIdAndStageSequence(
            String flowInstanceId, int stageSequence);

    List<HearingFlowStageEntity> findAllByFlowInstanceIdOrderByStageSequenceAsc(
            String flowInstanceId);
}
