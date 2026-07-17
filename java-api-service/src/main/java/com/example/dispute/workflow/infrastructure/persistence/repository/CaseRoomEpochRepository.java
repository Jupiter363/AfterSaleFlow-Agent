package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRoomEpochRepository extends JpaRepository<CaseRoomEpochEntity, String> {

    Optional<CaseRoomEpochEntity> findByCaseIdAndRoomTypeAndRoomEpoch(
            String caseId, RoomType roomType, long roomEpoch);

    Optional<CaseRoomEpochEntity> findByCaseIdAndRoomTypeAndLifecycleStatus(
            String caseId, RoomType roomType, EpochLifecycleStatus lifecycleStatus);
}
