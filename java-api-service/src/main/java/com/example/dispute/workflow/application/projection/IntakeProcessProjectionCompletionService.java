package com.example.dispute.workflow.application.projection;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.infrastructure.persistence.repository.DomainOperationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Repairs a formally committed Intake command after CONTROL observes Temporal consumption. */
@Service
public class IntakeProcessProjectionCompletionService {

    private static final String FINALIZATION_RESULT_PREFIX =
            "urn:intake:finalization-receipt:";
    private static final String PROJECTION_OPERATION_PREFIX = "projection:intake:";
    private static final String CONTROL_PLANE_MACRO_SENTINEL = "CONTROL_PLANE_SHADOW";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DomainOperationRepository operationRepository;
    private final FencedProcessProjectionService projectionService;

    public IntakeProcessProjectionCompletionService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DomainOperationRepository operationRepository,
            FencedProcessProjectionService projectionService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.operationRepository =
                Objects.requireNonNull(operationRepository, "operationRepository");
        this.projectionService =
                Objects.requireNonNull(projectionService, "projectionService");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CompletionResult> recover(
            ReconciliationTarget target, AuthoritativeProcessObservation observation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observation, "observation");
        try {
            return recoverInternal(target, observation);
        } catch (ProjectionWriteRejectedException failure) {
            throw failure;
        } catch (IntakeFinalizationRejectedException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_RECEIPT_REJECTED",
                    "committed Intake receipt failed canonical validation",
                    failure);
        } catch (DomainOperationConflictException failure) {
            throw rejected(
                    failure.reasonCode(),
                    "projection operation conflicts with committed Intake evidence",
                    failure);
        } catch (DomainOperationInProgressException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_OPERATION_IN_PROGRESS",
                    "projection operation is not yet replayable",
                    failure);
        } catch (IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_REQUEST_INVALID",
                    "committed Intake evidence cannot form a projection request",
                    failure);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletionResult completeConsumedEvent(
            CompleteConsumedIntakeProjectionCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            return completeConsumedEventInternal(command);
        } catch (ProjectionWriteRejectedException failure) {
            throw failure;
        } catch (IntakeFinalizationRejectedException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_RECEIPT_REJECTED",
                    "committed Intake receipt failed canonical validation",
                    failure);
        } catch (DomainOperationConflictException failure) {
            throw rejected(
                    failure.reasonCode(),
                    "projection operation conflicts with committed Intake evidence",
                    failure);
        } catch (DomainOperationInProgressException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_REQUEST_INVALID",
                    "consumed Intake event cannot form a projection request",
                    failure);
        }
    }

    private CompletionResult completeConsumedEventInternal(
            CompleteConsumedIntakeProjectionCommand command) {
        CompletionEvidence evidence =
                loadByEventId(command.tenantSurrogate(), command.caseId(), command.eventId())
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "INTAKE_PROJECTION_EVIDENCE_MISSING",
                                                "consumed Intake event has no committed finalization evidence"));
        String operationKey = projectionOperationKey(evidence);
        operationRepository.lockTenantOperationKey(command.tenantSurrogate(), operationKey);

        CompletionEvidence current =
                loadByEventId(command.tenantSurrogate(), command.caseId(), command.eventId())
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "INTAKE_PROJECTION_EVIDENCE_CHANGED",
                                                "committed Intake evidence changed while acquiring the projection lock"));
        if (!operationKey.equals(projectionOperationKey(current))) {
            throw rejected(
                    "INTAKE_PROJECTION_EVIDENCE_CHANGED",
                    "committed Intake evidence changed while acquiring the projection lock");
        }
        validateConsumedEventAuthority(current, command);
        return complete(current, operationKey);
    }

    private Optional<CompletionResult> recoverInternal(
            ReconciliationTarget target, AuthoritativeProcessObservation observation) {
        if (observation.activeRoomType() != RoomType.INTAKE
                || observation.activeRoomRevision() == null
                || observation.activeFencingToken() < 1
                || observation.processRevision() < 1
                || observation.lastCommandSequence() < 1) {
            return Optional.empty();
        }
        CompletionEvidence evidence =
                loadByCommandSequence(
                                target.tenantSurrogate(),
                                target.caseId(),
                                observation.lastCommandSequence())
                        .orElse(null);
        if (evidence == null) {
            return Optional.empty();
        }
        String operationKey = projectionOperationKey(evidence);
        operationRepository.lockTenantOperationKey(target.tenantSurrogate(), operationKey);

        CompletionEvidence current =
                loadByCommandSequence(
                                target.tenantSurrogate(),
                                target.caseId(),
                                observation.lastCommandSequence())
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "INTAKE_PROJECTION_EVIDENCE_CHANGED",
                                                "committed Intake evidence changed while acquiring the projection lock"));
        if (!operationKey.equals(projectionOperationKey(current))) {
            throw rejected(
                    "INTAKE_PROJECTION_EVIDENCE_CHANGED",
                    "committed Intake evidence changed while acquiring the projection lock");
        }
        validateRecoveryAuthority(current, target, observation);
        return Optional.of(complete(current, operationKey));
    }

    private CompletionResult complete(CompletionEvidence evidence, String operationKey) {
        validateFormalEvidence(evidence);
        IntakeFinalizationReceipt receipt = evidence.receipt();
        long newProcessRevision = increment(receipt.processRevision(), "process revision");
        long newRoomRevision = increment(receipt.roomRevision(), "room revision");
        ApplyProjectionCommand command =
                projectionCommand(evidence, operationKey, newProcessRevision, newRoomRevision);
        ProjectionOperation existing = findProjectionOperation(evidence, operationKey);
        if (existing != null) {
            validateProjectionReplay(evidence, existing, command);
            return new CompletionResult(
                    CompletionOutcome.IDEMPOTENT_REPLAY,
                    receipt.logicalRunId(),
                    receipt.attemptId(),
                    newProcessRevision,
                    newRoomRevision,
                    evidence.eventSequence(),
                    existing.resultUri(),
                    existing.resultSha256(),
                    existing.completedAt().toInstant());
        }

        validateFreshProjectionAuthority(evidence);
        ApplyProjectionResult applied = projectionService.apply(command);
        return new CompletionResult(
                CompletionOutcome.APPLIED,
                receipt.logicalRunId(),
                receipt.attemptId(),
                applied.processRevision(),
                applied.roomRevision(),
                evidence.eventSequence(),
                applied.resultRef(),
                applied.resultSha256(),
                applied.appliedAt());
    }

    private Optional<CompletionEvidence> loadByCommandSequence(
            String tenantSurrogate, String caseId, long commandSequence) {
        return load(
                tenantSurrogate,
                caseId,
                "command_row.case_command_sequence = :selector",
                commandSequence);
    }

    private Optional<CompletionEvidence> loadByEventId(
            String tenantSurrogate, String caseId, String eventId) {
        return load(
                tenantSurrogate,
                caseId,
                "formal_event.id = :selector",
                eventId);
    }

    private Optional<CompletionEvidence> load(
            String tenantSurrogate,
            String caseId,
            String selectorPredicate,
            Object selector) {
        String sql =
                """
                select command_row.command_id,
                       command_row.request_hash as command_request_hash,
                       command_row.case_command_sequence,
                       command_row.command_type,
                       command_row.command_status,
                       command_row.result_uri as command_result_uri,
                       command_row.result_sha256 as command_result_sha256,
                       command_row.expected_process_revision,
                       command_row.room_epoch,
                       epoch.process_revision as epoch_process_revision,
                       epoch.room_revision as epoch_room_revision,
                       epoch.fencing_token,
                       epoch.writer_mode as epoch_writer_mode,
                       epoch.lifecycle_status,
                       epoch.provisioning_status,
                       epoch.temporal_workflow_id as epoch_workflow_id,
                       epoch.temporal_run_id as epoch_run_id,
                       epoch.room_temporal_run_id as epoch_room_run_id,
                       epoch.temporal_build_id as epoch_build_id,
                       projection.macro_phase,
                       projection.current_room,
                       projection.room_phase,
                       projection.writer_mode as projection_writer_mode,
                       projection.writer_activation_status,
                       projection.process_revision as projection_process_revision,
                       projection.room_epoch as projection_room_epoch,
                       projection.fencing_token as projection_fencing_token,
                       projection.last_command_sequence,
                       projection.last_case_event_sequence,
                       projection.projected_deadline_at,
                       projection.temporal_workflow_id as projection_workflow_id,
                       projection.temporal_run_id as projection_run_id,
                       projection.temporal_build_id as projection_build_id,
                       formal_operation.operation_key as formal_operation_key,
                       formal_operation.process_revision as formal_process_revision,
                       formal_operation.fencing_token as formal_fencing_token,
                       formal_operation.result_uri as formal_result_uri,
                       formal_operation.result_sha256 as formal_result_sha256,
                       formal_event.id as formal_event_id,
                       formal_event.sequence_no as formal_event_sequence,
                       formal_event.event_type as formal_event_type,
                       cast(formal_event.event_json as text) as formal_event_json
                  from case_command command_row
                  join case_room_epoch epoch
                    on epoch.tenant_surrogate = command_row.tenant_surrogate
                   and epoch.case_id = command_row.case_id
                   and epoch.room_type = command_row.room_type
                   and epoch.room_epoch = command_row.room_epoch
                  join case_process_projection projection
                    on projection.case_id = command_row.case_id
                  join domain_operation formal_operation
                    on formal_operation.tenant_surrogate = command_row.tenant_surrogate
                   and formal_operation.case_id = command_row.case_id
                   and formal_operation.room_type = 'INTAKE'
                   and formal_operation.room_epoch = command_row.room_epoch
                   and formal_operation.operation_type = 'INTAKE_TURN_FINALIZE'
                   and formal_operation.operation_status = 'COMPLETED'
                  join case_timeline_event formal_event
                    on formal_event.case_id = command_row.case_id
                   and formal_operation.result_uri =
                       'urn:intake:finalization-receipt:' || formal_event.id
                  where command_row.tenant_surrogate = :tenantSurrogate
                   and command_row.case_id = :caseId
                   and command_row.room_type = 'INTAKE'
                   and formal_event.event_json -> 'receipt' ->> 'command_id' =
                       command_row.command_id
                    and %s
                  order by formal_operation.completed_at desc
                 """
                        .formatted(selectorPredicate);
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("tenantSurrogate", tenantSurrogate)
                        .addValue("caseId", caseId)
                        .addValue("selector", selector);
        List<CompletionEvidence> rows =
                jdbc.query(
                        sql,
                        parameters,
                        (result, ignored) ->
                                new CompletionEvidence(
                                        tenantSurrogate,
                                        caseId,
                                        result.getString("command_id"),
                                        result.getString("command_request_hash"),
                                        result.getLong("case_command_sequence"),
                                        result.getString("command_type"),
                                        result.getString("command_status"),
                                        result.getString("command_result_uri"),
                                        result.getString("command_result_sha256"),
                                        result.getLong("expected_process_revision"),
                                        result.getLong("room_epoch"),
                                        result.getLong("epoch_process_revision"),
                                        result.getLong("epoch_room_revision"),
                                        result.getLong("fencing_token"),
                                        result.getString("epoch_writer_mode"),
                                        result.getString("lifecycle_status"),
                                        result.getString("provisioning_status"),
                                        result.getString("epoch_workflow_id"),
                                        result.getString("epoch_run_id"),
                                        result.getString("epoch_room_run_id"),
                                        result.getString("epoch_build_id"),
                                        result.getString("macro_phase"),
                                        result.getString("current_room"),
                                        result.getString("room_phase"),
                                        result.getString("projection_writer_mode"),
                                        result.getString("writer_activation_status"),
                                        result.getLong("projection_process_revision"),
                                        result.getLong("projection_room_epoch"),
                                        result.getLong("projection_fencing_token"),
                                        result.getLong("last_command_sequence"),
                                        result.getLong("last_case_event_sequence"),
                                        result.getObject(
                                                "projected_deadline_at", OffsetDateTime.class),
                                        result.getString("projection_workflow_id"),
                                        result.getString("projection_run_id"),
                                        result.getString("projection_build_id"),
                                        result.getString("formal_operation_key"),
                                        result.getLong("formal_process_revision"),
                                        result.getLong("formal_fencing_token"),
                                        result.getString("formal_result_uri"),
                                        result.getString("formal_result_sha256"),
                                        result.getString("formal_event_id"),
                                        result.getLong("formal_event_sequence"),
                                        result.getString("formal_event_type"),
                                        decodeReceipt(
                                                result.getString("formal_event_json"),
                                                result.getString("formal_event_type"))));
        if (rows.size() > 1) {
            throw rejected(
                    "INTAKE_PROJECTION_EVIDENCE_AMBIGUOUS",
                    "more than one committed Intake finalization matches the command");
        }
        return rows.stream().findFirst();
    }

    private ProjectionOperation findProjectionOperation(
            CompletionEvidence evidence, String operationKey) {
        List<ProjectionOperation> rows =
                jdbc.query(
                        """
                         select operation_type, operation_status, request_hash, case_id,
                                room_type, room_epoch,
                                process_revision, fencing_token, result_uri, result_sha256,
                                completed_at
                          from domain_operation
                         where tenant_surrogate = :tenantSurrogate
                            and operation_key = :operationKey
                        """,
                        Map.of(
                                "tenantSurrogate", evidence.tenantSurrogate(),
                                "operationKey", operationKey),
                        (result, ignored) ->
                                new ProjectionOperation(
                                        result.getString("operation_type"),
                                        result.getString("operation_status"),
                                        result.getString("request_hash"),
                                        result.getString("case_id"),
                                        result.getString("room_type"),
                                        result.getLong("room_epoch"),
                                        result.getLong("process_revision"),
                                        result.getLong("fencing_token"),
                                        result.getString("result_uri"),
                                        result.getString("result_sha256"),
                                        result.getObject("completed_at", OffsetDateTime.class)));
        if (rows.size() > 1) {
            throw rejected(
                    "INTAKE_PROJECTION_OPERATION_AMBIGUOUS",
                    "projection operation key is not unique");
        }
        return rows.stream().findFirst().orElse(null);
    }

    private void validateRecoveryAuthority(
            CompletionEvidence evidence,
            ReconciliationTarget target,
            AuthoritativeProcessObservation observation) {
        long newProcessRevision = increment(evidence.receipt().processRevision(), "process revision");
        long newRoomRevision = increment(evidence.receipt().roomRevision(), "room revision");
        if (!target.tenantSurrogate().equals(evidence.tenantSurrogate())
                || !target.caseId().equals(evidence.caseId())
                || !target.temporalWorkflowId().equals(evidence.temporalWorkflowId())
                || !observation.tenantSurrogate().equals(evidence.tenantSurrogate())
                || !observation.caseId().equals(evidence.caseId())
                || !observation.temporalWorkflowId().equals(evidence.temporalWorkflowId())
                || !sameText(
                        observation.verifiedFirstExecutionRunId(),
                        evidence.temporalRunId())
                || !sameText(
                        observation.verifiedActiveChildRunId(),
                        evidence.roomTemporalRunId())
                || observation.activeRoomType() != RoomType.INTAKE
                || observation.activeRoomEpoch() != evidence.roomEpoch()
                || observation.activeFencingToken() != evidence.fencingToken()
                || observation.activeRoomRevision() == null
                || observation.activeRoomRevision() != newRoomRevision
                || observation.processRevision() != newProcessRevision
                || observation.lastCommandSequence() != evidence.commandSequence()
                || observation.lastCaseEventSequence() != evidence.eventSequence()
                || !CONTROL_PLANE_MACRO_SENTINEL.equals(observation.macroPhase())) {
            throw rejected(
                    "INTAKE_PROJECTION_TEMPORAL_AUTHORITY_MISMATCH",
                    "Temporal observation does not match the committed Intake completion");
        }
    }

    private void validateConsumedEventAuthority(
            CompletionEvidence evidence,
            CompleteConsumedIntakeProjectionCommand command) {
        long newProcessRevision = increment(evidence.receipt().processRevision(), "process revision");
        long newRoomRevision = increment(evidence.receipt().roomRevision(), "room revision");
        if (!command.tenantSurrogate().equals(evidence.tenantSurrogate())
                || !command.caseId().equals(evidence.caseId())
                || !command.eventId().equals(evidence.eventId())
                || command.caseEventSequence() != evidence.eventSequence()
                || !canonicalEventType(command.eventType()).equals(evidence.eventType())
                || command.lastCommandSequence() != evidence.commandSequence()
                || command.roomEpoch() != evidence.roomEpoch()
                || command.fencingToken() != evidence.fencingToken()
                || command.processRevision() != newProcessRevision
                || command.roomRevision() != newRoomRevision
                || !command.temporalWorkflowId().equals(evidence.temporalWorkflowId())
                || !command.firstExecutionRunId().equals(evidence.temporalRunId())
                || !command.activeChildRunId().equals(evidence.roomTemporalRunId())) {
            throw rejected(
                    "INTAKE_PROJECTION_TEMPORAL_AUTHORITY_MISMATCH",
                    "consumed Intake event does not match committed completion authority");
        }
    }

    private static String canonicalEventType(String eventType) {
        return switch (eventType) {
            case "TURN_NEEDS_INPUT", "INTAKE_TURN_NEEDS_INPUT" -> "TURN_NEEDS_INPUT";
            case "TURN_READY_TO_CONFIRM", "INTAKE_TURN_READY_TO_CONFIRM" ->
                    "TURN_READY_TO_CONFIRM";
            default -> eventType;
        };
    }

    private void validateFormalEvidence(CompletionEvidence evidence) {
        IntakeFinalizationReceipt receipt = evidence.receipt();
        receipt.requireCanonicalHash();
        String expectedResultUri = FINALIZATION_RESULT_PREFIX + evidence.eventId();
        if (!"INTAKE_MESSAGE".equals(evidence.commandType())
                || !evidence.formalOperationKey().equals(receipt.operationKey())
                || !evidence.tenantSurrogate().equals(receipt.tenantSurrogate())
                || !evidence.caseId().equals(receipt.caseId())
                || !evidence.commandId().equals(receipt.commandId())
                || evidence.roomEpoch() != receipt.roomEpoch()
                || evidence.expectedProcessRevision() != receipt.processRevision()
                || evidence.formalProcessRevision() != receipt.processRevision()
                || evidence.fencingToken() != receipt.fencingToken()
                || evidence.formalFencingToken() != receipt.fencingToken()
                || !expectedResultUri.equals(evidence.formalResultUri())
                || !receipt.receiptHash().equals(evidence.formalResultSha256())
                || !receipt.domainEventIds().equals(List.of(evidence.eventId()))
                || !List.of("TURN_NEEDS_INPUT", "TURN_READY_TO_CONFIRM")
                        .contains(evidence.eventType())) {
            throw rejected(
                    "INTAKE_PROJECTION_FORMAL_EVIDENCE_INVALID",
                    "committed Intake receipt does not bind the projection transition");
        }
    }

    private void validateFreshProjectionAuthority(CompletionEvidence evidence) {
        IntakeFinalizationReceipt receipt = evidence.receipt();
        boolean exact =
                "ORCHESTRATION_ACCEPTED".equals(evidence.commandStatus())
                        && evidence.commandResultUri() == null
                        && evidence.commandResultSha256() == null
                        && "TEMPORAL".equals(evidence.epochWriterMode())
                        && "ACTIVE".equals(evidence.lifecycleStatus())
                        && "READY".equals(evidence.provisioningStatus())
                        && "TEMPORAL".equals(evidence.projectionWriterMode())
                        && "READY".equals(evidence.writerActivationStatus())
                        && "INTAKE".equals(evidence.currentRoom())
                        && evidence.epochProcessRevision() == receipt.processRevision()
                        && evidence.projectionProcessRevision() == receipt.processRevision()
                        && evidence.epochRoomRevision() == receipt.roomRevision()
                        && evidence.projectionRoomEpoch() == evidence.roomEpoch()
                        && evidence.projectionFencingToken() == evidence.fencingToken()
                        && evidence.lastCommandSequence()
                                == Math.max(0, evidence.commandSequence() - 1)
                        && evidence.lastCaseEventSequence() <= evidence.eventSequence()
                        && sameText(evidence.temporalWorkflowId(), evidence.projectionWorkflowId())
                        && sameText(evidence.temporalRunId(), evidence.projectionRunId())
                        && sameText(evidence.temporalBuildId(), evidence.projectionBuildId());
        if (!exact) {
            throw rejected(
                    "INTAKE_PROJECTION_FRESH_AUTHORITY_MISMATCH",
                    "Intake projection authority changed before completion");
        }
    }

    private void validateProjectionReplay(
            CompletionEvidence evidence,
            ProjectionOperation operation,
            ApplyProjectionCommand command) {
        boolean exact =
                "APPLY_PROCESS_PROJECTION".equals(operation.operationType())
                        && "COMPLETED".equals(operation.status())
                        && ProcessProjectionRequestHasher.hash(command)
                                .equals(operation.requestHash())
                        && evidence.caseId().equals(operation.caseId())
                        && "INTAKE".equals(operation.roomType())
                        && evidence.roomEpoch() == operation.roomEpoch()
                        && command.newProcessRevision() == operation.processRevision()
                        && evidence.fencingToken() == operation.fencingToken()
                        && evidence.formalResultUri().equals(operation.resultUri())
                        && evidence.formalResultSha256().equals(operation.resultSha256())
                        && operation.completedAt() != null
                        && "APPLIED".equals(evidence.commandStatus())
                        && evidence.formalResultUri().equals(evidence.commandResultUri())
                        && evidence.formalResultSha256().equals(evidence.commandResultSha256());
        if (!exact) {
            throw rejected(
                    "INTAKE_PROJECTION_REPLAY_CONFLICT",
                    "persisted projection completion conflicts with the Intake receipt");
        }
        if (evidence.epochProcessRevision() < command.newProcessRevision()
                || evidence.epochRoomRevision() < command.newRoomRevision()
                || evidence.projectionProcessRevision() < command.newProcessRevision()
                || evidence.lastCommandSequence() < evidence.commandSequence()
                || evidence.lastCaseEventSequence() < evidence.eventSequence()) {
            throw rejected(
                    "INTAKE_PROJECTION_REPLAY_STATE_STALE",
                    "persisted projection completion is not reflected in current state");
        }
    }

    private IntakeFinalizationReceipt decodeReceipt(
            String eventJson, String persistedEventType) {
        try {
            JsonNode document = objectMapper.readTree(eventJson);
            if (document == null || !document.isObject()) {
                throw new JsonProcessingException("formal event is not an object") {};
            }
            String eventType = document.required("event_type").textValue();
            IntakeFinalizationReceipt receipt =
                    objectMapper.treeToValue(
                            document.required("receipt"), IntakeFinalizationReceipt.class);
            if (!Objects.equals(eventType, persistedEventType) || receipt == null) {
                throw new JsonProcessingException("formal event receipt is incomplete") {};
            }
            return receipt;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_RECEIPT_INVALID",
                    "committed Intake event contains an invalid receipt",
                    failure);
        }
    }

    private static String projectedRoomPhase(String eventType) {
        return switch (eventType) {
            case "TURN_NEEDS_INPUT" -> "WAITING_PARTY";
            case "TURN_READY_TO_CONFIRM" -> "READY_TO_CONFIRM";
            default ->
                    throw rejected(
                            "INTAKE_PROJECTION_EVENT_TYPE_UNSUPPORTED",
                            "formal Intake event cannot drive a projection completion");
        };
    }

    private static ApplyProjectionCommand projectionCommand(
            CompletionEvidence evidence,
            String operationKey,
            long newProcessRevision,
            long newRoomRevision) {
        IntakeFinalizationReceipt receipt = evidence.receipt();
        return new ApplyProjectionCommand(
                "apply-process-projection.v1",
                operationKey,
                evidence.tenantSurrogate(),
                evidence.caseId(),
                evidence.commandId(),
                evidence.commandRequestHash(),
                RoomType.INTAKE,
                evidence.roomEpoch(),
                evidence.fencingToken(),
                receipt.processRevision(),
                newProcessRevision,
                receipt.roomRevision(),
                newRoomRevision,
                evidence.macroPhase(),
                evidence.currentRoom(),
                projectedRoomPhase(evidence.eventType()),
                evidence.commandSequence(),
                evidence.eventSequence(),
                evidence.projectedDeadlineAt() == null
                        ? null
                        : evidence.projectedDeadlineAt().toInstant(),
                evidence.temporalWorkflowId(),
                evidence.temporalRunId(),
                evidence.temporalRunId(),
                evidence.temporalBuildId(),
                evidence.formalResultUri(),
                evidence.formalResultSha256());
    }

    private static String projectionOperationKey(CompletionEvidence evidence) {
        return PROJECTION_OPERATION_PREFIX
                + sha256(
                        String.join(
                                "|",
                                evidence.tenantSurrogate(),
                                evidence.caseId(),
                                evidence.commandId(),
                                evidence.formalResultSha256()));
    }

    private static long increment(long value, String field) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw rejected(
                    "INTAKE_PROJECTION_REVISION_OVERFLOW", field + " cannot advance", overflow);
        }
    }

    private static boolean sameText(String left, String right) {
        return left != null && left.equals(right);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static ProjectionWriteRejectedException rejected(String code, String message) {
        return new ProjectionWriteRejectedException(code, message);
    }

    private static ProjectionWriteRejectedException rejected(
            String code, String message, Throwable cause) {
        ProjectionWriteRejectedException failure =
                new ProjectionWriteRejectedException(code, message);
        failure.initCause(cause);
        return failure;
    }

    public record CompletionResult(
            CompletionOutcome outcome,
            String logicalRunId,
            String attemptId,
            long processRevision,
            long roomRevision,
            long lastCaseEventSequence,
            String resultRef,
            String resultSha256,
            Instant completedAt) {

        public CompletionResult {
            Objects.requireNonNull(outcome, "outcome");
            if (logicalRunId == null
                    || logicalRunId.isBlank()
                    || attemptId == null
                    || attemptId.isBlank()) {
                throw new IllegalArgumentException("completion AgentRun identity is invalid");
            }
            if (processRevision < 1 || roomRevision < 1 || lastCaseEventSequence < 1) {
                throw new IllegalArgumentException("completion revisions are invalid");
            }
            if (resultRef == null
                    || !resultRef.startsWith("urn:")
                    || resultSha256 == null
                    || !resultSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("completion result reference is invalid");
            }
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    public enum CompletionOutcome {
        APPLIED,
        IDEMPOTENT_REPLAY
    }

    private record CompletionEvidence(
            String tenantSurrogate,
            String caseId,
            String commandId,
            String commandRequestHash,
            long commandSequence,
            String commandType,
            String commandStatus,
            String commandResultUri,
            String commandResultSha256,
            long expectedProcessRevision,
            long roomEpoch,
            long epochProcessRevision,
            long epochRoomRevision,
            long fencingToken,
            String epochWriterMode,
            String lifecycleStatus,
            String provisioningStatus,
            String temporalWorkflowId,
            String temporalRunId,
            String roomTemporalRunId,
            String temporalBuildId,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            String projectionWriterMode,
            String writerActivationStatus,
            long projectionProcessRevision,
            long projectionRoomEpoch,
            long projectionFencingToken,
            long lastCommandSequence,
            long lastCaseEventSequence,
            OffsetDateTime projectedDeadlineAt,
            String projectionWorkflowId,
            String projectionRunId,
            String projectionBuildId,
            String formalOperationKey,
            long formalProcessRevision,
            long formalFencingToken,
            String formalResultUri,
            String formalResultSha256,
            String eventId,
            long eventSequence,
            String eventType,
            IntakeFinalizationReceipt receipt) {}

    private record ProjectionOperation(
            String operationType,
            String status,
            String requestHash,
            String caseId,
            String roomType,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String resultUri,
            String resultSha256,
            OffsetDateTime completedAt) {}
}
