package com.example.dispute.workflow.application.projection;

import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus.ORCHESTRATION_ACCEPTED;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus.PENDING_ORCHESTRATION;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus.ACTIVE;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OperationStatus.COMPLETED;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OperationStatus.STARTED;

import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.DomainOperationEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.DomainOperationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FencedProcessProjectionService {

    private static final String OPERATION_TYPE = "APPLY_PROCESS_PROJECTION";

    private final DomainOperationRepository operationRepository;
    private final CaseCommandRepository commandRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final Clock clock;

    public FencedProcessProjectionService(
            DomainOperationRepository operationRepository,
            CaseCommandRepository commandRepository,
            CaseProcessProjectionRepository projectionRepository,
            CaseRoomEpochRepository roomEpochRepository,
            Clock clock) {
        this.operationRepository = operationRepository;
        this.commandRepository = commandRepository;
        this.projectionRepository = projectionRepository;
        this.roomEpochRepository = roomEpochRepository;
        this.clock = clock;
    }

    @Transactional
    public ApplyProjectionResult apply(ApplyProjectionCommand request) {
        Objects.requireNonNull(request, "request must not be null");
        String operationRequestHash = ProcessProjectionRequestHasher.hash(request);
        operationRepository.lockTenantOperationKey(
                request.tenantSurrogate(), request.operationKey());
        DomainOperationEntity existing =
                operationRepository
                        .findByTenantSurrogateAndOperationKey(
                                request.tenantSurrogate(), request.operationKey())
                        .orElse(null);
        if (existing != null) {
            return replay(existing, request, operationRequestHash);
        }

        CaseCommandEntity command = requireCommand(request);
        CaseProcessProjectionEntity projection = requireProjection(request);
        CaseRoomEpochEntity epoch = requireEpoch(request);
        validateProjectionFence(projection, request);
        validateEpochFence(epoch, request);

        OffsetDateTime appliedAt = now();
        DomainOperationEntity operation =
                DomainOperationEntity.started(
                        operationId(operationRequestHash),
                        request.operationKey(),
                        request.tenantSurrogate(),
                        request.caseId(),
                        command.getId(),
                        OPERATION_TYPE,
                        request.roomType(),
                        request.roomEpoch(),
                        request.newProcessRevision(),
                        request.fencingToken(),
                        operationRequestHash,
                        appliedAt);
        operationRepository.saveAndFlush(operation);

        int epochUpdated =
                roomEpochRepository.advanceFencedEpoch(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.roomType().name(),
                        request.roomEpoch(),
                        request.fencingToken(),
                        request.expectedProcessRevision(),
                        request.newProcessRevision(),
                        request.expectedRoomRevision(),
                        request.newRoomRevision(),
                        request.temporalWorkflowId(),
                        request.expectedTemporalRunId(),
                        request.temporalRunId(),
                        request.temporalBuildId(),
                        appliedAt);
        if (epochUpdated != 1) {
            throw rejected(
                    "EPOCH_CAS_REJECTED",
                    "room epoch changed before the fenced projection operation committed");
        }

        int projectionUpdated =
                projectionRepository.advanceFencedProjection(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.roomEpoch(),
                        request.fencingToken(),
                        request.expectedProcessRevision(),
                        request.newProcessRevision(),
                        request.macroPhase(),
                        request.currentRoom(),
                        request.roomPhase(),
                        request.lastCommandSequence(),
                        request.lastCaseEventSequence(),
                        request.projectedDeadlineAt() == null
                                ? null
                                : OffsetDateTime.ofInstant(
                                        request.projectedDeadlineAt(), ZoneOffset.UTC),
                        request.temporalWorkflowId(),
                        request.expectedTemporalRunId(),
                        request.temporalRunId(),
                        request.temporalBuildId(),
                        request.projectionRef(),
                        request.projectionSha256(),
                        appliedAt);
        if (projectionUpdated != 1) {
            throw rejected(
                    "PROJECTION_CAS_REJECTED",
                    "process projection changed before the fenced operation committed");
        }

        command.markApplied(
                request.projectionRef(), request.projectionSha256(), appliedAt);
        operation.markCompleted(
                request.projectionRef(), request.projectionSha256(), appliedAt);
        operationRepository.saveAndFlush(operation);
        return result(request, operation, ApplyProjectionOutcome.APPLIED);
    }

    private ApplyProjectionResult replay(
            DomainOperationEntity operation,
            ApplyProjectionCommand request,
            String operationRequestHash) {
        if (!operation.getRequestHash().equals(operationRequestHash)
                || !operation.getCaseId().equals(request.caseId())
                || !OPERATION_TYPE.equals(operation.getOperationType())
                || operation.getRoomType() != request.roomType()
                || operation.getRoomEpoch() != request.roomEpoch()
                || operation.getProcessRevision() != request.newProcessRevision()
                || operation.getFencingToken() != request.fencingToken()) {
            throw new DomainOperationConflictException(
                    "DOMAIN_OPERATION_HASH_CONFLICT",
                    "operation key is already bound to another canonical request");
        }
        if (operation.getOperationStatus() == STARTED) {
            throw new DomainOperationInProgressException(
                    "domain operation is still in progress");
        }
        if (operation.getOperationStatus() != COMPLETED
                || operation.getCompletedAt() == null) {
            throw new DomainOperationConflictException(
                    "DOMAIN_OPERATION_TERMINAL_CONFLICT",
                    "operation key is bound to a non-replayable terminal operation");
        }
        return result(request, operation, ApplyProjectionOutcome.IDEMPOTENT_REPLAY);
    }

    private CaseCommandEntity requireCommand(ApplyProjectionCommand request) {
        CaseCommandEntity command =
                commandRepository
                        .findByTenantSurrogateAndCommandId(
                                request.tenantSurrogate(), request.commandId())
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "COMMAND_NOT_FOUND",
                                                "projection command is absent from the Java ledger"));
        if (!command.getCaseId().equals(request.caseId())) {
            throw rejected("COMMAND_SCOPE_MISMATCH", "command belongs to another case");
        }
        if (!command.getRequestHash().equals(request.commandRequestHash())) {
            throw rejected("COMMAND_HASH_MISMATCH", "command request hash does not match the ledger");
        }
        if (command.getRoomType() != request.roomType()
                || command.getRoomEpoch() != request.roomEpoch()) {
            throw rejected("COMMAND_EPOCH_MISMATCH", "command targets another room epoch");
        }
        if (command.getExpectedProcessRevision() != request.expectedProcessRevision()) {
            throw rejected(
                    "COMMAND_REVISION_MISMATCH",
                    "command expected revision does not match the projection request");
        }
        if (command.getCaseCommandSequence() != request.lastCommandSequence()) {
            throw rejected(
                    "COMMAND_SEQUENCE_MISMATCH",
                    "projection command sequence does not match the ledger");
        }
        if (command.getCommandStatus() != PENDING_ORCHESTRATION
                && command.getCommandStatus() != ORCHESTRATION_ACCEPTED) {
            throw rejected(
                    "COMMAND_STATUS_REJECTED",
                    "command is not in an applicable orchestration state");
        }
        return command;
    }

    private CaseProcessProjectionEntity requireProjection(
            ApplyProjectionCommand request) {
        return projectionRepository
                .findById(request.caseId())
                .orElseThrow(
                        () ->
                                rejected(
                                        "PROJECTION_NOT_FOUND",
                                        "case process projection is unavailable"));
    }

    private CaseRoomEpochEntity requireEpoch(ApplyProjectionCommand request) {
        return roomEpochRepository
                .findByCaseIdAndRoomTypeAndRoomEpoch(
                        request.caseId(), request.roomType(), request.roomEpoch())
                .orElseThrow(
                        () ->
                                rejected(
                                        "ROOM_EPOCH_STALE",
                                        "room epoch is unavailable or no longer current"));
    }

    private static void validateProjectionFence(
            CaseProcessProjectionEntity projection,
            ApplyProjectionCommand request) {
        if (!projection.getTenantSurrogate().equals(request.tenantSurrogate())) {
            throw rejected("PROJECTION_SCOPE_MISMATCH", "projection belongs to another tenant");
        }
        if (projection.getWriterMode() != TEMPORAL) {
            throw rejected(
                    "WRITER_MODE_REJECTED",
                    "only a TEMPORAL epoch can accept Temporal projection writes");
        }
        if (projection.getRoomEpoch() != request.roomEpoch()) {
            throw rejected("ROOM_EPOCH_STALE", "projection room epoch has changed");
        }
        if (projection.getFencingToken() != request.fencingToken()) {
            throw rejected("FENCING_TOKEN_STALE", "projection fencing token has changed");
        }
        if (projection.getProcessRevision() != request.expectedProcessRevision()) {
            throw rejected("PROCESS_REVISION_STALE", "projection revision has changed");
        }
        if (!Objects.equals(
                projection.getTemporalWorkflowId(), request.temporalWorkflowId())) {
            throw rejected("WORKFLOW_BINDING_STALE", "projection workflow binding has changed");
        }
        if (!Objects.equals(
                projection.getTemporalRunId(), request.expectedTemporalRunId())) {
            throw rejected("WORKFLOW_RUN_STALE", "projection run binding has changed");
        }
        if (!Objects.equals(
                projection.getTemporalBuildId(), request.temporalBuildId())) {
            throw rejected("WORKFLOW_BUILD_STALE", "projection build binding has changed");
        }
        if (projection.getLastCommandSequence() > request.lastCommandSequence()
                || projection.getLastCaseEventSequence()
                        > request.lastCaseEventSequence()) {
            throw rejected("PROJECTION_SEQUENCE_STALE", "projection sequence would move backward");
        }
    }

    private static void validateEpochFence(
            CaseRoomEpochEntity epoch, ApplyProjectionCommand request) {
        if (!epoch.getTenantSurrogate().equals(request.tenantSurrogate())) {
            throw rejected("EPOCH_SCOPE_MISMATCH", "room epoch belongs to another tenant");
        }
        if (epoch.getWriterMode() != TEMPORAL || epoch.getLifecycleStatus() != ACTIVE) {
            throw rejected(
                    "EPOCH_WRITER_REJECTED",
                    "room epoch is not an active TEMPORAL writer");
        }
        if (epoch.getFencingToken() != request.fencingToken()) {
            throw rejected("FENCING_TOKEN_STALE", "room epoch fencing token has changed");
        }
        if (epoch.getProcessRevision() != request.expectedProcessRevision()) {
            throw rejected("PROCESS_REVISION_STALE", "room epoch process revision has changed");
        }
        if (epoch.getRoomRevision() != request.expectedRoomRevision()) {
            throw rejected("ROOM_REVISION_STALE", "room revision has changed");
        }
        if (!Objects.equals(epoch.getTemporalWorkflowId(), request.temporalWorkflowId())) {
            throw rejected("WORKFLOW_BINDING_STALE", "room workflow binding has changed");
        }
        if (!Objects.equals(epoch.getTemporalRunId(), request.expectedTemporalRunId())) {
            throw rejected("WORKFLOW_RUN_STALE", "room run binding has changed");
        }
        if (!Objects.equals(epoch.getTemporalBuildId(), request.temporalBuildId())) {
            throw rejected("WORKFLOW_BUILD_STALE", "room build binding has changed");
        }
    }

    private static ApplyProjectionResult result(
            ApplyProjectionCommand request,
            DomainOperationEntity operation,
            ApplyProjectionOutcome outcome) {
        return new ApplyProjectionResult(
                "apply-process-projection-result.v1",
                request.operationKey(),
                outcome,
                request.newProcessRevision(),
                request.newRoomRevision(),
                request.fencingToken(),
                request.temporalWorkflowId(),
                request.temporalRunId(),
                operation.getResultUri(),
                operation.getResultSha256(),
                operation.getCompletedAt().toInstant());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static String operationId(String requestHash) {
        return "DOP_" + requestHash.substring(0, 60);
    }

    private static ProjectionWriteRejectedException rejected(
            String reasonCode, String message) {
        return new ProjectionWriteRejectedException(reasonCode, message);
    }
}
