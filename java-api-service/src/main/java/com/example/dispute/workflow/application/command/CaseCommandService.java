package com.example.dispute.workflow.application.command;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.application.epoch.RoomEpochReadiness;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandOutboxRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseCommandService {

    private static final Pattern CASE_ID = Pattern.compile("CASE_[A-Za-z0-9]{1,59}");
    private static final Pattern COMMAND_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<CommandStatus> REVISION_RESERVING_STATUSES =
            Set.of(
                    CommandStatus.PENDING_ORCHESTRATION,
                    CommandStatus.ORCHESTRATION_ACCEPTED);
    private final FulfillmentCaseRepository caseRepository;
    private final CaseCommandRepository commandRepository;
    private final CaseCommandOutboxRepository outboxRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final AuditLogRepository auditLogRepository;
    private final TenantAuthority tenantAuthority;
    private final CaseCommandDeliveryTrigger deliveryTrigger;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CaseCommandService(
            FulfillmentCaseRepository caseRepository,
            CaseCommandRepository commandRepository,
            CaseCommandOutboxRepository outboxRepository,
            CaseProcessProjectionRepository projectionRepository,
            CaseRoomEpochRepository roomEpochRepository,
            AuditLogRepository auditLogRepository,
            TenantAuthority tenantAuthority,
            CaseCommandDeliveryTrigger deliveryTrigger,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.projectionRepository = projectionRepository;
        this.roomEpochRepository = roomEpochRepository;
        this.auditLogRepository = auditLogRepository;
        this.tenantAuthority = tenantAuthority;
        this.deliveryTrigger = deliveryTrigger;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = IdempotencyConflictException.class)
    public CaseCommandAcceptance accept(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            String requestId,
            String incomingTraceparent) {
        requireIdentifier(caseId, CASE_ID, "caseId");
        requireIdentifier(commandId, COMMAND_ID, "commandId");
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String traceparent =
                TraceparentBridge.resolve(incomingTraceparent, traceId, requestId);
        String tenantSurrogate = tenantAuthority.tenantSurrogate();

        FulfillmentCaseEntity initialCase = findCase(caseId);
        ActorRef actorRef = CaseCommandAuthorization.authorize(initialCase, command, actor);
        String requestHash =
                CaseCommandRequestHasher.hash(
                        tenantSurrogate, caseId, commandId, command, actorRef);

        var existing =
                commandRepository.findByTenantSurrogateAndCommandId(
                        tenantSurrogate, commandId);
        if (existing.isPresent()) {
            return replayOrReject(
                    existing.get(), requestHash, actor, caseId, traceId, requestId);
        }

        FulfillmentCaseEntity lockedCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        actorRef = CaseCommandAuthorization.authorize(lockedCase, command, actor);
        requestHash =
                CaseCommandRequestHasher.hash(
                        tenantSurrogate, caseId, commandId, command, actorRef);

        commandRepository.lockTenantCommandId(tenantSurrogate, commandId);
        existing =
                commandRepository.findByTenantSurrogateAndCommandId(
                        tenantSurrogate, commandId);
        if (existing.isPresent()) {
            return replayOrReject(
                    existing.get(), requestHash, actor, caseId, traceId, requestId);
        }

        validateAndReserveRevision(tenantSurrogate, caseId, command);
        OffsetDateTime acceptedAt =
                OffsetDateTime.ofInstant(
                        clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        if (!command.deadlineAt().isAfter(acceptedAt.toInstant())) {
            throw new IllegalArgumentException("deadlineAt must be in the future");
        }

        long sequence =
                Math.addExact(
                        commandRepository
                                .findFirstByCaseIdOrderByCaseCommandSequenceDesc(caseId)
                                .map(CaseCommandEntity::getCaseCommandSequence)
                                .orElse(0L),
                        1L);
        CaseCommandRef reference =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        commandId,
                        tenantSurrogate,
                        caseId,
                        sequence,
                        command.commandType(),
                        command.roomType(),
                        command.roomEpoch(),
                        actorRef,
                        command.payloadRef(),
                        command.expectedProcessRevision(),
                        acceptedAt.toInstant(),
                        command.deadlineAt(),
                        traceparent,
                        requestHash);

        CaseCommandEntity commandEntity =
                CaseCommandEntity.pending(
                        id("CMD_"), reference, json(actorRef.actorScopes()), acceptedAt);
        commandRepository.save(commandEntity);
        CaseCommandOutboxEntity outbox =
                CaseCommandOutboxEntity.pending(
                        id("COUT_"),
                        commandEntity.getId(),
                        reference,
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                tenantSurrogate, caseId),
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        acceptedAt);
        outboxRepository.save(outbox);
        deliveryTrigger.deliveryRequested(outbox.getId());
        return acceptance(commandEntity, reference, false);
    }

    private CaseCommandAcceptance replayOrReject(
            CaseCommandEntity existing,
            String requestHash,
            AuthenticatedActor actor,
            String requestedCaseId,
            String traceId,
            String requestId) {
        if (existing.getRequestHash().equals(requestHash)) {
            return acceptance(
                    existing,
                    CaseCommandReferenceMapper.fromEntity(existing, objectMapper),
                    true);
        }
        auditLogRepository.save(
                AuditLogEntity.idempotencyConflict(
                        id("AUD_"),
                        requestedCaseId,
                        traceId,
                        requestId,
                        actor.actorId(),
                        actor.role().name(),
                        existing.getCommandId(),
                        json(
                                Map.of(
                                        "case_id", existing.getCaseId(),
                                        "request_hash", existing.getRequestHash())),
                        json(
                                Map.of(
                                        "case_id", requestedCaseId,
                                        "request_hash", requestHash)),
                        json(
                                Map.of(
                                        "reason_code", "COMMAND_ID_HASH_MISMATCH",
                                        "tenant_surrogate", existing.getTenantSurrogate()))));
        throw new IdempotencyConflictException(
                "command id is already bound to a different authorized request");
    }

    private void validateAndReserveRevision(
            String tenantSurrogate, String caseId, AcceptCaseCommand command) {
        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                caseId, command.roomType(), command.roomEpoch())
                        .orElseThrow(
                                () ->
                                        invalidState(
                                                "active room epoch is unavailable",
                                                Map.of("case_id", caseId)));
        if (!tenantSurrogate.equals(epoch.getTenantSurrogate())) {
            throw new ForbiddenException("room epoch is outside the active tenant authority");
        }
        if (epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE) {
            throw invalidState(
                    "room epoch is not active",
                    Map.of("room_epoch_status", epoch.getLifecycleStatus().name()));
        }

        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        invalidState(
                                                "case process projection is unavailable",
                                                Map.of("case_id", caseId)));
        if (!tenantSurrogate.equals(projection.getTenantSurrogate())) {
            throw new ForbiddenException("case is outside the active tenant authority");
        }
        WriterMode writerMode = epoch.getWriterMode();
        if (writerMode == WriterMode.LEGACY) {
            throw invalidState(
                    "legacy room epochs do not accept Temporal commands",
                    Map.of("writer_mode", writerMode.name()));
        }
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId);
        if (projection.getWriterMode() != writerMode
                || projection.getFencingToken() != epoch.getFencingToken()
                || !Objects.equals(
                        projection.getTemporalWorkflowId(),
                        epoch.getTemporalWorkflowId())
                || !expectedWorkflowId.equals(epoch.getTemporalWorkflowId())) {
            throw invalidState(
                    "case process ownership binding is inconsistent",
                    Map.of(
                            "case_id", caseId,
                            "writer_mode", writerMode.name(),
                            "expected_workflow_id", expectedWorkflowId));
        }
        if (!RoomEpochReadiness.isTemporalReady(epoch, projection)) {
            throw invalidState(
                    "room epoch provisioning is not ready",
                    Map.of(
                            "case_id", caseId,
                            "writer_mode", writerMode.name(),
                            "epoch_provisioning_status",
                                    epoch.getProvisioningStatus().name(),
                            "projection_activation_status",
                                    projection.getWriterActivationStatus().name()));
        }
        if (projection.getProcessRevision() != command.expectedProcessRevision()
                || epoch.getProcessRevision() != command.expectedProcessRevision()) {
            throw invalidState(
                    "expected process revision is stale",
                    Map.of(
                            "expected_process_revision",
                            command.expectedProcessRevision(),
                            "current_process_revision",
                            projection.getProcessRevision(),
                            "epoch_process_revision",
                            epoch.getProcessRevision()));
        }
        if (projection.getRoomEpoch() != command.roomEpoch()
                || !roomMatchesProjection(command.roomType(), projection.getCurrentRoom())) {
            throw invalidState(
                    "command does not target the active room epoch",
                    Map.of(
                            "requested_room", command.roomType().name(),
                            "requested_room_epoch", command.roomEpoch(),
                            "current_room", String.valueOf(projection.getCurrentRoom()),
                            "current_room_epoch", projection.getRoomEpoch()));
        }
        if (writerMode == WriterMode.TEMPORAL
                && commandRepository
                .existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        caseId,
                        command.expectedProcessRevision(),
                        REVISION_RESERVING_STATUSES)) {
            throw invalidState(
                    "expected process revision is already reserved by an active command",
                    Map.of(
                            "case_id", caseId,
                            "expected_process_revision",
                            command.expectedProcessRevision()));
        }
    }

    private static CaseCommandAcceptance acceptance(
            CaseCommandEntity entity, CaseCommandRef reference, boolean replay) {
        return new CaseCommandAcceptance(
                reference,
                entity.getCommandStatus().name(),
                entity.getAcceptedAt().toInstant(),
                replay);
    }

    private FulfillmentCaseEntity findCase(String caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> caseNotFound(caseId));
    }

    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    private static BusinessException invalidState(
            String message, Map<String, Object> details) {
        return new BusinessException(ErrorCode.CASE_STATUS_INVALID, message, details);
    }

    private static boolean roomMatchesProjection(RoomType roomType, String currentRoom) {
        if (roomType == RoomType.REVIEW) {
            return "DRAFT".equals(currentRoom)
                    || "REVIEW".equals(currentRoom)
                    || "OUTCOME".equals(currentRoom);
        }
        return roomType.name().equals(currentRoom);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("command audit value cannot be serialized", exception);
        }
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireIdentifier(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
