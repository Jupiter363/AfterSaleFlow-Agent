package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseProcessProjectionRepository
        extends JpaRepository<CaseProcessProjectionEntity, String> {

    Optional<CaseProcessProjectionEntity> findByTemporalWorkflowId(String temporalWorkflowId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select projection from CaseProcessProjectionEntity projection where projection.caseId = :caseId")
    Optional<CaseProcessProjectionEntity> findByIdForUpdate(@Param("caseId") String caseId);

    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    update case_process_projection
                       set macro_phase = :macroPhase,
                           current_room = :currentRoom,
                           room_phase = :roomPhase,
                           process_revision = :newProcessRevision,
                           last_command_sequence = :lastCommandSequence,
                           last_case_event_sequence = :lastCaseEventSequence,
                           projected_deadline_at = :projectedDeadlineAt,
                           projection_ref = :projectionRef,
                           projection_sha256 = :projectionSha256,
                           projected_at = :projectedAt,
                           updated_at = :projectedAt,
                           version = version + 1
                     where case_id = :caseId
                       and tenant_surrogate = :tenantSurrogate
                       and writer_mode = 'TEMPORAL'
                       and room_epoch = :roomEpoch
                       and fencing_token = :fencingToken
                       and process_revision = :expectedProcessRevision
                       and process_revision < :newProcessRevision
                       and last_command_sequence <= :lastCommandSequence
                       and last_case_event_sequence <= :lastCaseEventSequence
                       and temporal_workflow_id = :temporalWorkflowId
                       and temporal_run_id = :expectedTemporalRunId
                       and temporal_build_id = :temporalBuildId
                    """,
            nativeQuery = true)
    int advanceFencedProjection(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("caseId") String caseId,
            @Param("roomEpoch") long roomEpoch,
            @Param("fencingToken") long fencingToken,
            @Param("expectedProcessRevision") long expectedProcessRevision,
            @Param("newProcessRevision") long newProcessRevision,
            @Param("macroPhase") String macroPhase,
            @Param("currentRoom") String currentRoom,
            @Param("roomPhase") String roomPhase,
            @Param("lastCommandSequence") long lastCommandSequence,
            @Param("lastCaseEventSequence") long lastCaseEventSequence,
            @Param("projectedDeadlineAt") java.time.OffsetDateTime projectedDeadlineAt,
            @Param("temporalWorkflowId") String temporalWorkflowId,
            @Param("expectedTemporalRunId") String expectedTemporalRunId,
            @Param("temporalBuildId") String temporalBuildId,
            @Param("projectionRef") String projectionRef,
            @Param("projectionSha256") String projectionSha256,
            @Param("projectedAt") java.time.OffsetDateTime projectedAt);

    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    insert into case_process_projection (
                        case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                        writer_mode, process_revision, room_epoch, fencing_token,
                        last_command_sequence, last_case_event_sequence,
                        projected_deadline_at, temporal_workflow_id, temporal_run_id,
                        temporal_build_id, projection_ref, projection_sha256,
                        projected_at, updated_at
                    )
                    select
                        :caseId, :tenantSurrogate, :macroPhase, :currentRoom, :roomPhase,
                        'TEMPORAL', :processRevision, :roomEpoch, :fencingToken,
                        :lastCommandSequence, :lastCaseEventSequence,
                        :projectedDeadlineAt, :temporalWorkflowId, :temporalRunId,
                        :temporalBuildId, :projectionRef, :projectionSha256,
                        :projectedAt, :projectedAt
                      from case_room_epoch epoch
                     where epoch.case_id = :caseId
                       and epoch.tenant_surrogate = :tenantSurrogate
                       and epoch.room_type = :roomType
                       and epoch.room_epoch = :roomEpoch
                       and epoch.writer_mode = 'TEMPORAL'
                       and epoch.lifecycle_status = 'ACTIVE'
                       and epoch.process_revision = :processRevision
                       and epoch.room_revision = :roomRevision
                       and epoch.fencing_token = :fencingToken
                       and epoch.temporal_workflow_id = :temporalWorkflowId
                       and epoch.temporal_run_id = :temporalRunId
                       and epoch.temporal_build_id = :temporalBuildId
                    on conflict do nothing
                    """,
            nativeQuery = true)
    int insertFencedProjection(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("caseId") String caseId,
            @Param("roomType") String roomType,
            @Param("roomEpoch") long roomEpoch,
            @Param("processRevision") long processRevision,
            @Param("roomRevision") long roomRevision,
            @Param("fencingToken") long fencingToken,
            @Param("macroPhase") String macroPhase,
            @Param("currentRoom") String currentRoom,
            @Param("roomPhase") String roomPhase,
            @Param("lastCommandSequence") long lastCommandSequence,
            @Param("lastCaseEventSequence") long lastCaseEventSequence,
            @Param("projectedDeadlineAt") java.time.OffsetDateTime projectedDeadlineAt,
            @Param("temporalWorkflowId") String temporalWorkflowId,
            @Param("temporalRunId") String temporalRunId,
            @Param("temporalBuildId") String temporalBuildId,
            @Param("projectionRef") String projectionRef,
            @Param("projectionSha256") String projectionSha256,
            @Param("projectedAt") java.time.OffsetDateTime projectedAt);
}
