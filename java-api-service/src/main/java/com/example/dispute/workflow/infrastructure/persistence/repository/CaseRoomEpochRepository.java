package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRoomEpochRepository extends JpaRepository<CaseRoomEpochEntity, String> {

    Optional<CaseRoomEpochEntity> findByCaseIdAndRoomTypeAndRoomEpoch(
            String caseId, RoomType roomType, long roomEpoch);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select epoch
              from CaseRoomEpochEntity epoch
             where epoch.caseId = :caseId
               and epoch.roomType = :roomType
               and epoch.roomEpoch = :roomEpoch
            """)
    Optional<CaseRoomEpochEntity> findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
            @Param("caseId") String caseId,
            @Param("roomType") RoomType roomType,
            @Param("roomEpoch") long roomEpoch);

    Optional<CaseRoomEpochEntity> findByCaseIdAndRoomTypeAndLifecycleStatus(
            String caseId, RoomType roomType, EpochLifecycleStatus lifecycleStatus);

    Optional<CaseRoomEpochEntity>
            findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                    String caseId,
                    RoomType roomType,
                    EpochLifecycleStatus lifecycleStatus);

    @Query(
            value =
                    """
                    select *
                      from case_room_epoch
                     where case_id = :caseId
                       and lifecycle_status = 'ACTIVE'
                     for update
                    """,
            nativeQuery = true)
    Optional<CaseRoomEpochEntity> findActiveByCaseIdForUpdate(
            @Param("caseId") String caseId);

    @Query(
            value =
                    """
                    select *
                      from case_room_epoch
                     where case_id = :caseId
                       and lifecycle_status in ('PREPARING', 'PROVISIONING', 'ACTIVE')
                     for update
                    """,
            nativeQuery = true)
    Optional<CaseRoomEpochEntity> findWriterSlotByCaseIdForUpdate(
            @Param("caseId") String caseId);

    @Query(
            value =
                    """
                    select *
                      from case_room_epoch
                     where temporal_workflow_id = :temporalWorkflowId
                       and lifecycle_status = 'ACTIVE'
                     for update
                    """,
            nativeQuery = true)
    Optional<CaseRoomEpochEntity> findByTemporalWorkflowIdForUpdate(
            @Param("temporalWorkflowId") String temporalWorkflowId);

    @Query(
            """
            select max(epoch.roomEpoch)
              from CaseRoomEpochEntity epoch
             where epoch.caseId = :caseId
               and epoch.roomType = :roomType
            """)
    Optional<Long> findMaxRoomEpoch(
            @Param("caseId") String caseId,
            @Param("roomType") RoomType roomType);

    @Query(
            """
            select max(epoch.fencingToken)
              from CaseRoomEpochEntity epoch
             where epoch.caseId = :caseId
            """)
    Optional<Long> findMaxFencingToken(@Param("caseId") String caseId);

    @Query(
            """
            select epoch
              from CaseRoomEpochEntity epoch
             where epoch.tenantSurrogate = :tenantSurrogate
               and epoch.caseId = :caseId
               and epoch.roomType = :roomType
               and epoch.activatedAt <= :occurredAt
               and (epoch.terminalAt is null or epoch.terminalAt >= :occurredAt)
             order by epoch.roomEpoch desc
            """)
    List<CaseRoomEpochEntity> findEpochAt(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("caseId") String caseId,
            @Param("roomType") RoomType roomType,
            @Param("occurredAt") OffsetDateTime occurredAt,
            Pageable pageable);

    @Query(
            """
            select epoch
              from CaseRoomEpochEntity epoch
             where epoch.tenantSurrogate = :tenantSurrogate
               and epoch.caseId = :caseId
               and epoch.roomId = :roomId
               and epoch.roomType = :roomType
            """)
    List<CaseRoomEpochEntity> findByRoomAuthority(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("caseId") String caseId,
            @Param("roomId") String roomId,
            @Param("roomType") RoomType roomType,
            Pageable pageable);

    List<CaseRoomEpochEntity>
            findByLifecycleStatusAndWriterModeInAndTemporalWorkflowIdIsNotNullOrderByUpdatedAtAsc(
                    EpochLifecycleStatus lifecycleStatus,
                    Collection<WriterMode> writerModes,
                    Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    update case_room_epoch
                       set process_revision = :newProcessRevision,
                           room_revision = :newRoomRevision,
                           updated_at = :updatedAt,
                           version = version + 1
                     where case_id = :caseId
                       and tenant_surrogate = :tenantSurrogate
                       and room_type = :roomType
                       and room_epoch = :roomEpoch
                       and writer_mode = 'TEMPORAL'
                       and lifecycle_status = 'ACTIVE'
                       and fencing_token = :fencingToken
                       and process_revision = :expectedProcessRevision
                       and process_revision < :newProcessRevision
                       and room_revision = :expectedRoomRevision
                       and room_revision <= :newRoomRevision
                       and temporal_workflow_id = :temporalWorkflowId
                       and temporal_run_id = :expectedTemporalRunId
                       and temporal_build_id = :temporalBuildId
                    """,
            nativeQuery = true)
    int advanceFencedEpoch(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("caseId") String caseId,
            @Param("roomType") String roomType,
            @Param("roomEpoch") long roomEpoch,
            @Param("fencingToken") long fencingToken,
            @Param("expectedProcessRevision") long expectedProcessRevision,
            @Param("newProcessRevision") long newProcessRevision,
            @Param("expectedRoomRevision") long expectedRoomRevision,
            @Param("newRoomRevision") long newRoomRevision,
            @Param("temporalWorkflowId") String temporalWorkflowId,
            @Param("expectedTemporalRunId") String expectedTemporalRunId,
            @Param("temporalBuildId") String temporalBuildId,
            @Param("updatedAt") java.time.OffsetDateTime updatedAt);
}
