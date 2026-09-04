package com.example.dispute.workflow.application.projection;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.persistence.repository.DomainOperationRepository;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphCommandEnvelope;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final ObjectMapper materialObjectMapper;
    private final ProductionGraphEnvelopeCodec envelopeCodec;
    private final DomainOperationRepository operationRepository;
    private final FencedProcessProjectionService projectionService;

    public IntakeProcessProjectionCompletionService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DomainOperationRepository operationRepository,
            FencedProcessProjectionService projectionService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.materialObjectMapper =
                this.objectMapper
                        .copy()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        this.envelopeCodec = new ProductionGraphEnvelopeCodec(this.objectMapper);
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
        Optional<CompletionEvidence> legacy =
                loadLegacy(tenantSurrogate, caseId, selectorPredicate, selector);
        if (legacy.isPresent()) {
            return legacy;
        }
        return loadRecoveredWinner(tenantSurrogate, caseId, selectorPredicate, selector);
    }

    private Optional<CompletionEvidence> loadLegacy(
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
                                mapCompletionEvidence(
                                        result,
                                        tenantSurrogate,
                                        caseId,
                                        CompletionAuthorityKind.LEGACY_EXACT_COMMAND,
                                        null));
        if (rows.size() > 1) {
            throw rejected(
                    "INTAKE_PROJECTION_EVIDENCE_AMBIGUOUS",
                    "more than one committed Intake finalization matches the command");
        }
        return rows.stream().findFirst();
    }

    private Optional<CompletionEvidence> loadRecoveredWinner(
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
                       cast(formal_event.event_json as text) as formal_event_json,
                       cast(
                           jsonb_build_object(
                               'originalCommand', to_jsonb(command_row),
                               'epoch', to_jsonb(epoch),
                               'projection', to_jsonb(projection),
                               'formalOperation', to_jsonb(formal_operation),
                               'formalEvent', to_jsonb(formal_event),
                               'run', to_jsonb(run_row),
                               'winnerAttempt', to_jsonb(winner_attempt),
                               'roomBinding', to_jsonb(room_binding),
                               'activation', to_jsonb(activation_row),
                               'winnerAdmission', to_jsonb(winner_admission),
                               'winnerCompletion', to_jsonb(winner_completion),
                               'manifest', to_jsonb(manifest_row),
                               'outputSnapshot', to_jsonb(output_snapshot),
                               'targetReceipt',
                                   to_jsonb(target_receipt) - 'receipt_canonical_bytes'
                           ) as text
                       ) as recovered_candidate_json,
                       target_receipt.receipt_canonical_bytes,
                       cast(lineage_proof.attempts_json as text) as recovered_attempts_json
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
                  join agent_run run_row
                    on run_row.id = formal_event.event_json -> 'receipt' ->> 'logical_run_id'
                  join agent_run_attempt winner_attempt
                    on winner_attempt.agent_run_id = run_row.id
                   and winner_attempt.id = run_row.committed_attempt_id
                  join agent_run_attempt root_attempt
                    on root_attempt.agent_run_id = run_row.id
                   and root_attempt.attempt_no = 1
                   and root_attempt.previous_attempt_id is null
                   and root_attempt.command_id = command_row.command_id
                  join production_runtime_room_epoch_binding room_binding
                    on room_binding.epoch_id = run_row.room_epoch_id
                  join production_runtime_activation activation_row
                    on activation_row.activation_id = room_binding.activation_id
                  join production_runtime_command_admission winner_admission
                    on winner_admission.activation_id = activation_row.activation_id
                   and winner_admission.command_id = winner_attempt.command_id
                  join production_runtime_command_completion winner_completion
                    on winner_completion.admission_id = winner_admission.admission_id
                  join production_runtime_finalization_receipt target_receipt
                    on target_receipt.activation_id = activation_row.activation_id
                   and target_receipt.logical_run_id = run_row.id
                  join agent_execution_manifest manifest_row
                    on manifest_row.id = run_row.committed_manifest_id
                  join immutable_payload_snapshot output_snapshot
                    on output_snapshot.id = manifest_row.output_snapshot_id
                  join lateral (
                      with recursive path_attempt as (
                          select winner_attempt.*
                          union all
                          select predecessor.*
                            from path_attempt successor
                            join agent_run_attempt predecessor
                              on predecessor.agent_run_id = successor.agent_run_id
                             and predecessor.id = successor.previous_attempt_id
                             and predecessor.attempt_no = successor.attempt_no - 1
                           where successor.attempt_no > 1
                      )
                      select jsonb_agg(
                                 jsonb_build_object(
                                     'attempt', to_jsonb(path_attempt),
                                     'admission', to_jsonb(path_admission),
                                     'material', to_jsonb(path_material),
                                     'contextCanonicalJson',
                                         path_material.context_canonical_json
                                 )
                                 order by path_attempt.attempt_no
                             ) as attempts_json
                        from path_attempt
                        join production_runtime_command_admission path_admission
                          on path_admission.activation_id = activation_row.activation_id
                         and path_admission.command_id = path_attempt.command_id
                        join production_runtime_intake_command_material path_material
                          on path_material.admission_id = path_admission.admission_id
                  ) lineage_proof on true
                 where command_row.tenant_surrogate = :tenantSurrogate
                   and command_row.case_id = :caseId
                   and command_row.room_type = 'INTAKE'
                   and formal_event.event_json -> 'receipt' ->> 'command_id' <>
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
                                mapCompletionEvidence(
                                        result,
                                        tenantSurrogate,
                                        caseId,
                                        CompletionAuthorityKind.TARGET_RECOVERED_WINNER_V1,
                                        new RecoveredWinnerProof(
                                                result.getString("recovered_candidate_json"),
                                                result.getBytes("receipt_canonical_bytes"),
                                                result.getString("recovered_attempts_json"))));
        if (rows.size() > 1) {
            throw rejected(
                    "INTAKE_PROJECTION_EVIDENCE_AMBIGUOUS",
                    "more than one recovered Intake finalization matches the command");
        }
        return rows.stream().findFirst();
    }

    private CompletionEvidence mapCompletionEvidence(
            ResultSet result,
            String tenantSurrogate,
            String caseId,
            CompletionAuthorityKind authorityKind,
            RecoveredWinnerProof recoveredWinnerProof)
            throws SQLException {
        return new CompletionEvidence(
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
                result.getObject("projected_deadline_at", OffsetDateTime.class),
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
                        result.getString("formal_event_type")),
                authorityKind,
                recoveredWinnerProof);
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
        switch (evidence.authorityKind()) {
            case LEGACY_EXACT_COMMAND -> {
                if (!evidence.commandId().equals(receipt.commandId())
                        || evidence.recoveredWinnerProof() != null) {
                    throw rejected(
                            "INTAKE_PROJECTION_FORMAL_EVIDENCE_INVALID",
                            "legacy Intake receipt does not bind the original command");
                }
            }
            case TARGET_RECOVERED_WINNER_V1 -> validateRecoveredWinnerAuthority(evidence);
        }
    }

    private void validateRecoveredWinnerAuthority(CompletionEvidence evidence) {
        try {
            RecoveredWinnerProof proof =
                    Objects.requireNonNull(
                            evidence.recoveredWinnerProof(), "recovered winner proof");
            IntakeFinalizationReceipt formalReceipt = evidence.receipt();
            if (evidence.commandId().equals(formalReceipt.commandId())) {
                throw new IllegalArgumentException(
                        "recovered winner must differ from the original command");
            }

            JsonNode candidate = readProofObject(proof.candidateJson(), "recovered candidate");
            JsonNode original = requiredObject(candidate, "originalCommand");
            JsonNode epoch = requiredObject(candidate, "epoch");
            JsonNode projection = requiredObject(candidate, "projection");
            JsonNode formalOperation = requiredObject(candidate, "formalOperation");
            JsonNode formalEvent = requiredObject(candidate, "formalEvent");
            JsonNode run = requiredObject(candidate, "run");
            JsonNode winnerRow = requiredObject(candidate, "winnerAttempt");
            JsonNode roomBinding = requiredObject(candidate, "roomBinding");
            JsonNode activation = requiredObject(candidate, "activation");
            JsonNode winnerAdmission = requiredObject(candidate, "winnerAdmission");
            JsonNode winnerCompletion = requiredObject(candidate, "winnerCompletion");
            JsonNode manifest = requiredObject(candidate, "manifest");
            JsonNode outputSnapshot = requiredObject(candidate, "outputSnapshot");
            JsonNode targetReceiptRow = requiredObject(candidate, "targetReceipt");

            validateRecoveredOriginalAuthority(
                    evidence,
                    original,
                    epoch,
                    projection,
                    formalOperation,
                    formalEvent);
            validateRecoveredActivationAuthority(evidence, epoch, roomBinding, activation, run);
            validateRecoveredRunAuthority(evidence, epoch, run);

            List<ValidatedRecoveredAttempt> attempts = new ArrayList<>();
            for (JsonNode rawAttempt : readProofArray(proof.attemptsJson(), "attempt lineage")) {
                attempts.add(
                        validateRecoveredAttempt(
                                evidence,
                                rawAttempt,
                                activation,
                                roomBinding,
                                run));
            }
            attempts.sort(
                    (left, right) ->
                            Long.compare(
                                    requiredLong(left.attempt(), "attempt_no"),
                                    requiredLong(right.attempt(), "attempt_no")));
            validateRecoveredLineage(evidence, run, winnerRow, attempts);
            validateRecoveredExecutionAuthority(original, run, attempts);

            ValidatedRecoveredAttempt winner = attempts.get(attempts.size() - 1);
            validateRecoveredWinnerTerminalAuthority(
                    evidence,
                    epoch,
                    activation,
                    run,
                    winner,
                    winnerAdmission,
                    winnerCompletion,
                    manifest,
                    outputSnapshot,
                    targetReceiptRow,
                    proof.targetReceiptCanonicalBytes(),
                    formalOperation,
                    formalEvent);
        } catch (ProjectionWriteRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_PROJECTION_RECOVERED_AUTHORITY_INVALID",
                    "recovered Intake winner does not have complete canonical authority",
                    failure);
        }
    }

    private void validateRecoveredOriginalAuthority(
            CompletionEvidence evidence,
            JsonNode original,
            JsonNode epoch,
            JsonNode projection,
            JsonNode formalOperation,
            JsonNode formalEvent) {
        requireText(original, "command_id", evidence.commandId());
        requireText(original, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(original, "case_id", evidence.caseId());
        requireText(original, "command_type", evidence.commandType());
        requireText(original, "room_type", "INTAKE");
        requireSha256(original, "request_hash");
        requireText(original, "request_hash", evidence.commandRequestHash());
        requireLong(original, "case_command_sequence", evidence.commandSequence());
        requireLong(original, "room_epoch", evidence.roomEpoch());
        requireLong(
                original, "expected_process_revision", evidence.expectedProcessRevision());

        requireText(epoch, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(epoch, "case_id", evidence.caseId());
        requireText(epoch, "room_type", "INTAKE");
        requireLong(epoch, "room_epoch", evidence.roomEpoch());
        requireLong(epoch, "process_revision", evidence.epochProcessRevision());
        requireLong(epoch, "room_revision", evidence.epochRoomRevision());
        requireLong(epoch, "fencing_token", evidence.fencingToken());
        requireText(epoch, "writer_mode", evidence.epochWriterMode());
        requireText(epoch, "temporal_workflow_id", evidence.temporalWorkflowId());
        requireText(epoch, "temporal_run_id", evidence.temporalRunId());
        requireText(epoch, "room_temporal_run_id", evidence.roomTemporalRunId());
        requireText(epoch, "temporal_build_id", evidence.temporalBuildId());

        requireText(projection, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(projection, "case_id", evidence.caseId());
        requireText(projection, "macro_phase", evidence.macroPhase());
        requireText(projection, "current_room", evidence.currentRoom());
        requireText(projection, "room_phase", evidence.roomPhase());
        requireText(projection, "writer_mode", evidence.projectionWriterMode());
        requireText(
                projection, "writer_activation_status", evidence.writerActivationStatus());
        requireLong(
                projection, "process_revision", evidence.projectionProcessRevision());
        requireLong(projection, "room_epoch", evidence.projectionRoomEpoch());
        requireLong(projection, "fencing_token", evidence.projectionFencingToken());
        requireLong(projection, "last_command_sequence", evidence.lastCommandSequence());
        requireLong(
                projection, "last_case_event_sequence", evidence.lastCaseEventSequence());
        requireText(
                projection, "temporal_workflow_id", evidence.projectionWorkflowId());
        requireText(projection, "temporal_run_id", evidence.projectionRunId());
        requireText(projection, "temporal_build_id", evidence.projectionBuildId());

        String formalCaseCommandRowId = optionalText(formalOperation, "case_command_id");
        if (formalCaseCommandRowId != null
                && !formalCaseCommandRowId.equals(requiredText(original, "id"))) {
            throw new IllegalArgumentException(
                    "formal operation references a foreign case command");
        }
        requireText(
                formalOperation, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(formalOperation, "case_id", evidence.caseId());
        requireText(formalOperation, "operation_type", "INTAKE_TURN_FINALIZE");
        requireText(formalOperation, "room_type", "INTAKE");
        requireText(formalOperation, "operation_status", "COMPLETED");
        requireText(
                formalOperation, "operation_key", evidence.formalOperationKey());
        requireText(formalOperation, "result_uri", evidence.formalResultUri());
        requireText(
                formalOperation, "result_sha256", evidence.formalResultSha256());
        requireLong(formalOperation, "room_epoch", evidence.roomEpoch());
        requireLong(
                formalOperation, "process_revision", evidence.formalProcessRevision());
        requireLong(
                formalOperation, "fencing_token", evidence.formalFencingToken());
        requirePresent(formalOperation, "completed_at");

        requireText(formalEvent, "id", evidence.eventId());
        requireText(formalEvent, "case_id", evidence.caseId());
        requireText(formalEvent, "event_type", evidence.eventType());
        requireText(formalEvent, "room_id", requiredText(epoch, "room_id"));
        requireLong(formalEvent, "sequence_no", evidence.eventSequence());
    }

    private void validateRecoveredActivationAuthority(
            CompletionEvidence evidence,
            JsonNode epoch,
            JsonNode roomBinding,
            JsonNode activation,
            JsonNode run) {
        requireText(activation, "contract_version", "production-runtime-activation.v1");
        requireText(activation, "execution_lane", "PRODUCTION");
        requireText(activation, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(activation, "formal_writer", "JAVA_FINALIZER_ONLY");
        if (!requiredBoolean(activation, "java_domain_commit_allowed")) {
            throw new IllegalArgumentException("target Java Domain commit is not authorized");
        }
        requireNonBlank(activation, "activation_id");
        requireSha256(activation, "manifest_hash");
        requireSha256(activation, "isolated_domain_db_binding_hash");
        requireNonBlank(activation, "graph_output_authority");

        requireText(roomBinding, "epoch_id", requiredText(epoch, "id"));
        requireText(
                roomBinding, "activation_id", requiredText(activation, "activation_id"));
        requireText(
                roomBinding,
                "activation_manifest_hash",
                requiredText(activation, "manifest_hash"));
        requireText(
                roomBinding, "execution_lane", requiredText(activation, "execution_lane"));
        requireText(
                roomBinding,
                "isolated_domain_db_binding_hash",
                requiredText(activation, "isolated_domain_db_binding_hash"));
        requireText(roomBinding, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(roomBinding, "case_id", evidence.caseId());
        requireText(roomBinding, "room_type", "INTAKE");
        requireLong(roomBinding, "room_epoch", evidence.roomEpoch());
        requireLong(roomBinding, "room_fencing_token", evidence.fencingToken());
        requireText(run, "room_epoch_id", requiredText(roomBinding, "epoch_id"));
    }

    private void validateRecoveredRunAuthority(
            CompletionEvidence evidence, JsonNode epoch, JsonNode run) {
        IntakeFinalizationReceipt receipt = evidence.receipt();
        requireText(run, "id", receipt.logicalRunId());
        requireText(run, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(run, "case_id", evidence.caseId());
        requireText(run, "room_id", requiredText(epoch, "room_id"));
        requireText(run, "room_type", "INTAKE");
        requireLong(run, "room_epoch", evidence.roomEpoch());
        requireLong(run, "process_revision", receipt.processRevision());
        requireLong(run, "fencing_token", evidence.fencingToken());
        requireText(run, "protocol", "agent-stream.v3");
        requireText(run, "executor_kind", "TEMPORAL_ACTIVITY");
        requireText(run, "lineage_schema_version", "agent-run-lineage.v1");
        requireText(run, "run_status", "COMPLETED");
        requireText(run, "finalization_status", "COMMITTED");
        requireText(
                run,
                "result_ready_attempt_id",
                requiredText(run, "committed_attempt_id"));
        requireText(run, "committed_attempt_id", receipt.attemptId());
        requireText(run, "final_result_hash", receipt.resultHash());
        requireSha256(run, "logical_input_hash");
        if (requiredLong(run, "attempt_limit") < 1) {
            throw new IllegalArgumentException("AgentRun attempt limit is invalid");
        }
        requireNonBlank(run, "committed_manifest_id");
        requireSha256(run, "committed_manifest_hash");
        requirePresent(run, "finalized_at");
    }

    private ValidatedRecoveredAttempt validateRecoveredAttempt(
            CompletionEvidence evidence,
            JsonNode raw,
            JsonNode activation,
            JsonNode roomBinding,
            JsonNode run) {
        JsonNode attempt = requiredObject(raw, "attempt");
        JsonNode admission = requiredObject(raw, "admission");
        JsonNode material = requiredObject(raw, "material");
        String canonicalContext = requiredText(raw, "contextCanonicalJson");
        requireText(material, "context_canonical_json", canonicalContext);
        JsonNode contextDocument =
                readProofObject(canonicalContext, "target Intake command material");
        if (!ContractJson.canonicalString(contextDocument).equals(canonicalContext)
                || !ContractJson.sha256Hex(contextDocument)
                        .equals(requiredText(material, "context_sha256"))) {
            throw new IllegalArgumentException(
                    "target Intake command material is not canonical");
        }

        IntakeCommandExecutionContext context =
                treeToValue(
                        materialObjectMapper,
                        contextDocument,
                        IntakeCommandExecutionContext.class,
                        "target Intake command context");
        if (!canonicalJsonEquals(
                        materialObjectMapper.valueToTree(context), contextDocument)
                || !"intake-command-execution-context.v2".equals(context.schemaVersion())
                || context.targetAgentRun() == null
                || context.branchOperation() != null
                || context.expectedProcessRevision() != null
                || context.expectedRoomRevision() != null
                || context.branchPinnedVersions() != null) {
            throw new IllegalArgumentException(
                    "target Intake command context is not an exact v2 AgentRun context");
        }
        IntakeTargetAgentRunContext target = context.targetAgentRun();
        ExecuteAgentRunRequest request = target.request();

        JsonNode commandDocument = attempt.required("command_json");
        if (commandDocument.isTextual()) {
            commandDocument =
                    readProofObject(commandDocument.textValue(), "attempt command");
        }
        if (!commandDocument.isObject()) {
            throw new IllegalArgumentException("attempt command is not an object");
        }
        RoomGraphCommand command =
                treeToValue(
                        objectMapper,
                        commandDocument,
                        RoomGraphCommand.class,
                        "attempt graph command");
        if (!canonicalJsonEquals(objectMapper.valueToTree(command), commandDocument)
                || !request.command().equals(command)) {
            throw new IllegalArgumentException(
                    "attempt command differs from its immutable execution request");
        }
        ProductionGraphCommandEnvelope envelope =
                envelopeCodec.wrapCommand(
                        target.activationId(), target.roomFencingToken(), command);

        long attemptNo = requiredLong(attempt, "attempt_no");
        requireText(attempt, "agent_run_id", requiredText(run, "id"));
        requireText(attempt, "attempt_status", requiredText(attempt, "attempt_status"));
        requireText(attempt, "executor_kind", "TEMPORAL_ACTIVITY");
        requireText(
                attempt,
                "lineage_schema_version",
                "agent-run-attempt-lineage.v1");
        requireText(attempt, "command_id", command.commandId());
        requireSha256(attempt, "request_hash");
        requireSha256(attempt, "command_request_hash");
        requireText(attempt, "request_hash", command.requestHash());
        requireText(attempt, "command_request_hash", command.requestHash());
        requireText(
                attempt, "logical_input_hash", requiredText(run, "logical_input_hash"));
        requireText(attempt, "graph_key", command.graphKey());
        requireText(attempt, "graph_version", command.graphVersion());
        requireText(
                attempt,
                "checkpoint_schema_version",
                command.checkpointSchemaVersion());
        requireText(
                attempt,
                "prompt_version",
                command.invocationContext().promptProfileId());
        requireText(
                attempt,
                "model_profile_id",
                command.invocationContext().modelProfileId());
        requireText(
                attempt,
                "output_schema_version",
                command.invocationContext().outputSchemaVersion());
        requireText(
                attempt,
                "policy_version",
                command.invocationContext().policyVersion());
        requireText(
                attempt,
                "guardrail_version",
                command.invocationContext().guardrailVersion());

        if (!requiredText(attempt, "id").equals(command.attemptId())
                || attemptNo != request.attemptNo()
                || !requiredText(run, "id").equals(request.agentRunId())
                || requiredLong(run, "attempt_limit") != request.attemptLimit()
                || !requiredText(run, "protocol").equals(request.streamProtocol())
                || !requiredText(run, "logical_input_hash")
                        .equals(request.logicalInputHash())
                || !sameNullableText(
                        optionalText(attempt, "previous_attempt_id"),
                        request.previousAttemptId())
                || requiredBoolean(attempt, "reset_required") != request.resetRequired()
                || requiredLong(attempt, "public_sequence_offset")
                        != request.publicSequenceOffset()) {
            throw new IllegalArgumentException(
                    "attempt row differs from its immutable execution request");
        }
        if (!evidence.tenantSurrogate().equals(command.tenantSurrogate())
                || !evidence.caseId().equals(command.caseId())
                || command.roomType() != RoomType.INTAKE
                || command.roomEpoch() != evidence.roomEpoch()
                || command.processRevision() != evidence.receipt().processRevision()
                || !requiredText(run, "id").equals(command.logicalRunId())) {
            throw new IllegalArgumentException(
                    "attempt command differs from the original case authority");
        }

        requireText(material, "admission_id", requiredText(admission, "admission_id"));
        requireText(material, "material_schema_version", "production-runtime-intake-command-material.v1");
        requireText(
                material,
                "context_schema_version",
                "intake-command-execution-context.v2");
        requireText(
                admission,
                "activation_id",
                requiredText(activation, "activation_id"));
        requireText(
                admission,
                "activation_manifest_hash",
                requiredText(activation, "manifest_hash"));
        requireText(
                admission,
                "execution_lane",
                requiredText(activation, "execution_lane"));
        requireText(
                admission,
                "isolated_domain_db_binding_hash",
                requiredText(activation, "isolated_domain_db_binding_hash"));
        requireText(admission, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(admission, "case_id", evidence.caseId());
        requireText(admission, "command_id", command.commandId());
        requireText(admission, "command_hash", envelope.commandHash());
        requireText(
                admission, "command_envelope_hash", envelope.commandEnvelopeHash());
        requireLong(admission, "room_epoch", evidence.roomEpoch());
        requireLong(admission, "room_fencing_token", evidence.fencingToken());
        requirePresent(admission, "admitted_at");

        for (String field :
                List.of(
                        "activation_id",
                        "activation_manifest_hash",
                        "execution_lane",
                        "isolated_domain_db_binding_hash",
                        "tenant_surrogate",
                        "case_id",
                        "command_id",
                        "command_hash",
                        "command_envelope_hash")) {
            requireText(material, field, requiredText(admission, field));
        }
        requireText(material, "room_type", "INTAKE");
        requireLong(material, "room_epoch", evidence.roomEpoch());
        requireLong(material, "room_fencing_token", evidence.fencingToken());
        requireText(
                material,
                "activation_id",
                requiredText(roomBinding, "activation_id"));

        String expectedTargetSchema =
                attemptNo == 1
                        ? IntakeTargetAgentRunContext.INITIAL_SCHEMA_VERSION
                        : IntakeTargetAgentRunContext.RETRY_SCHEMA_VERSION;
        if (!expectedTargetSchema.equals(target.schemaVersion())
                || !IntakeTargetAgentRunContext.TARGET_LANE.equals(target.executionLane())
                || !requiredText(activation, "activation_id").equals(target.activationId())
                || !requiredText(activation, "manifest_hash")
                        .equals(target.activationManifestHash())
                || target.roomFencingToken() != evidence.fencingToken()
                || target.expectedProcessRevision() != evidence.receipt().processRevision()
                || target.expectedRoomRevision() != evidence.receipt().roomRevision()
                || !requiredText(activation, "graph_binding_hash")
                        .equals(target.graphBindingHash())
                || !requiredText(activation, "graph_code_build_id")
                        .equals(target.graphCodeBuildId())
                || !envelope.commandHash().equals(target.commandHash())
                || !envelope.commandEnvelopeHash().equals(target.commandEnvelopeHash())) {
            throw new IllegalArgumentException(
                    "target AgentRun context differs from activation or command authority");
        }
        requireText(activation, "case_build_id", target.caseBuildId());
        requireText(activation, "control_build_id", target.controlBuildId());
        requireText(activation, "agent_build_id", target.agentBuildId());

        return new ValidatedRecoveredAttempt(
                attempt, admission, material, context, target, request, command, envelope);
    }

    private void validateRecoveredLineage(
            CompletionEvidence evidence,
            JsonNode run,
            JsonNode winnerRow,
            List<ValidatedRecoveredAttempt> attempts) {
        long winnerAttemptNo = requiredLong(winnerRow, "attempt_no");
        if (winnerAttemptNo < 2
                || winnerAttemptNo > requiredLong(run, "attempt_limit")
                || attempts.size() != winnerAttemptNo) {
            throw new IllegalArgumentException(
                    "recovered winner lineage does not contain every bounded attempt");
        }
        Set<Long> attemptNumbers = new HashSet<>();
        Set<String> attemptIds = new HashSet<>();
        Set<String> commandIds = new HashSet<>();
        for (int index = 0; index < attempts.size(); index++) {
            ValidatedRecoveredAttempt current = attempts.get(index);
            JsonNode row = current.attempt();
            long expectedAttemptNo = index + 1L;
            long attemptNo = requiredLong(row, "attempt_no");
            String attemptId = requiredText(row, "id");
            String commandId = requiredText(row, "command_id");
            if (attemptNo != expectedAttemptNo
                    || !attemptNumbers.add(attemptNo)
                    || !attemptIds.add(attemptId)
                    || !commandIds.add(commandId)) {
                throw new IllegalArgumentException(
                        "recovered winner lineage is not unique and contiguous");
            }
            if (index == 0) {
                if (optionalText(row, "previous_attempt_id") != null
                        || !evidence.commandId().equals(commandId)
                        || requiredBoolean(row, "reset_required")
                        || requiredLong(row, "public_sequence_offset") != 0) {
                    throw new IllegalArgumentException(
                            "recovered lineage root does not bind the original case command");
                }
            } else {
                ValidatedRecoveredAttempt predecessor = attempts.get(index - 1);
                if (!requiredText(predecessor.attempt(), "id")
                                .equals(optionalText(row, "previous_attempt_id"))
                        || !requiredText(predecessor.attempt(), "id")
                                .equals(current.request().previousAttemptId())) {
                    throw new IllegalArgumentException(
                            "recovered winner predecessor chain is broken");
                }
            }
            if (index < attempts.size() - 1) {
                String status = requiredText(row, "attempt_status");
                requiredText(row, "error_code");
                if (!Set.of("FAILED", "ABORTED", "CANCELLED").contains(status)
                        || !"CREATE_NEXT_ATTEMPT".equals(optionalText(row, "termination_code"))
                        || !requiredBoolean(row, "error_retryable")
                        || !hasPresent(row, "completed_at")) {
                    throw new IllegalArgumentException(
                            "recovered predecessor was not terminally advanced");
                }
            }
        }

        ValidatedRecoveredAttempt winner = attempts.get(attempts.size() - 1);
        if (!requiredText(winnerRow, "id").equals(requiredText(winner.attempt(), "id"))
                || !requiredText(run, "committed_attempt_id")
                        .equals(requiredText(winner.attempt(), "id"))
                || !evidence.receipt().attemptId()
                        .equals(requiredText(winner.attempt(), "id"))
                || !evidence.receipt().commandId().equals(winner.command().commandId())
                || !"COMPLETED".equals(requiredText(winner.attempt(), "attempt_status"))
                || !evidence.receipt().resultHash()
                        .equals(requiredText(winner.attempt(), "result_hash"))
                || optionalText(winner.attempt(), "termination_code") != null
                || !hasPresent(winner.attempt(), "completed_at")) {
            throw new IllegalArgumentException(
                    "recovered winner does not match the committed AgentRun attempt");
        }
    }

    private void validateRecoveredExecutionAuthority(
            JsonNode original, JsonNode run, List<ValidatedRecoveredAttempt> attempts) {
        ValidatedRecoveredAttempt root = attempts.get(0);
        RoomGraphCommand rootCommand = root.command();
        requireText(original, "command_id", rootCommand.commandId());
        requireSha256(original, "payload_sha256");
        for (ValidatedRecoveredAttempt current : attempts) {
            RoomGraphCommand command = current.command();
            RoomGraphCommand.SnapshotRef eventRef =
                    Objects.requireNonNull(
                            command.eventRef(), "lineage command event reference");
            requireText(original, "payload_schema_version", eventRef.schemaVersion());
            requireText(original, "payload_uri", eventRef.uri());
            requireText(original, "payload_sha256", eventRef.sha256());
            requireLong(original, "payload_size_bytes", eventRef.sizeBytes());
            requireInstant(original, "deadline_at", command.deadlineAt());
            if (!current.context().threadId().equals(command.threadId())
                    || current.context().deadlineEpochMillis()
                            != command.deadlineAt().toEpochMilli()) {
                throw new IllegalArgumentException(
                        "recovered attempt context does not bind its Graph command");
            }
        }

        requireSha256(run, "request_hash");
        requireText(run, "request_hash", rootCommand.requestHash());
    }

    private void validateRecoveredWinnerTerminalAuthority(
            CompletionEvidence evidence,
            JsonNode epoch,
            JsonNode activation,
            JsonNode run,
            ValidatedRecoveredAttempt winner,
            JsonNode winnerAdmission,
            JsonNode winnerCompletion,
            JsonNode manifest,
            JsonNode outputSnapshot,
            JsonNode targetReceiptRow,
            byte[] targetReceiptCanonicalBytes,
            JsonNode formalOperation,
            JsonNode formalEvent) {
        IntakeFinalizationReceipt formalReceipt = evidence.receipt();
        RoomGraphCommand command = winner.command();
        ProductionGraphCommandEnvelope envelope = winner.envelope();

        requireText(
                winnerAdmission,
                "admission_id",
                requiredText(winner.admission(), "admission_id"));
        for (String field :
                List.of(
                        "activation_id",
                        "activation_manifest_hash",
                        "execution_lane",
                        "isolated_domain_db_binding_hash",
                        "tenant_surrogate",
                        "case_id",
                        "command_id",
                        "command_hash",
                        "command_envelope_hash")) {
            requireText(
                    winnerAdmission, field, requiredText(winner.admission(), field));
        }
        requireLong(winnerAdmission, "room_epoch", evidence.roomEpoch());
        requireLong(
                winnerAdmission, "room_fencing_token", evidence.fencingToken());

        requireText(
                winnerCompletion,
                "admission_id",
                requiredText(winnerAdmission, "admission_id"));
        requireText(
                winnerCompletion,
                "activation_id",
                requiredText(activation, "activation_id"));
        requireText(winnerCompletion, "command_id", command.commandId());
        requireText(winnerCompletion, "command_hash", envelope.commandHash());
        requireText(
                winnerCompletion,
                "command_envelope_hash",
                envelope.commandEnvelopeHash());
        requirePresent(winnerCompletion, "completed_at");

        requireText(activation, "graph_key", command.graphKey());
        requireText(activation, "graph_version", command.graphVersion());
        requireText(
                activation,
                "graph_checkpoint_schema_version",
                command.checkpointSchemaVersion());

        ProductionFinalizationReceipt targetReceipt =
                ProductionFinalizationReceiptCodec.decodeCanonical(
                        Objects.requireNonNull(
                                targetReceiptCanonicalBytes,
                                "target receipt canonical bytes"));
        if (!MessageDigest.isEqual(
                targetReceiptCanonicalBytes,
                ProductionFinalizationReceiptCodec.canonicalBytes(targetReceipt))) {
            throw new IllegalArgumentException("target receipt bytes changed after decoding");
        }
        validateStoredTargetReceipt(
                evidence,
                activation,
                run,
                winner,
                targetReceiptRow,
                targetReceipt);
        requireText(
                winnerCompletion, "completion_hash", targetReceipt.receiptHash());

        validateRecoveredManifest(
                evidence,
                activation,
                run,
                winner,
                manifest,
                outputSnapshot,
                targetReceipt);
        validateRecoveredFormalEvent(
                evidence,
                formalOperation,
                formalEvent,
                command,
                targetReceipt,
                formalReceipt);
    }

    private void validateStoredTargetReceipt(
            CompletionEvidence evidence,
            JsonNode activation,
            JsonNode run,
            ValidatedRecoveredAttempt winner,
            JsonNode row,
            ProductionFinalizationReceipt receipt) {
        requireText(row, "schema_version", receipt.schemaVersion());
        requireNonBlank(row, "receipt_id");
        requireText(row, "execution_lane", receipt.executionLane());
        requireText(row, "activation_id", receipt.activationId());
        requireText(
                row,
                "activation_manifest_hash",
                requiredText(activation, "manifest_hash"));
        requireText(row, "tenant_surrogate", receipt.tenantSurrogate());
        requireText(row, "case_id", receipt.caseId());
        requireText(row, "room_type", receipt.roomType().name());
        requireLong(row, "room_epoch", receipt.roomEpoch());
        requireLong(row, "room_fencing_token", receipt.roomFencingToken());
        requireLong(row, "process_revision", receipt.processRevision());
        requireLong(row, "stage_sequence", receipt.stageSequence());
        requireText(row, "logical_run_id", receipt.logicalRunId());
        requireText(row, "attempt_id", receipt.attemptId());
        requireText(row, "command_hash", receipt.commandHash());
        requireText(row, "command_envelope_hash", receipt.commandEnvelopeHash());
        requireText(row, "graph_key", receipt.graphKey());
        requireText(row, "graph_version", receipt.graphVersion());
        requireText(
                row,
                "checkpoint_schema_version",
                receipt.checkpointSchemaVersion());
        requireText(row, "checkpoint_id", receipt.checkpointId());
        requireText(row, "result_hash", receipt.resultHash());
        requireText(row, "proposal_hash", receipt.proposalHash());
        requireText(row, "result_envelope_hash", receipt.resultEnvelopeHash());
        requireText(row, "agent_run_manifest_id", receipt.agentRunManifestId());
        requireText(row, "agent_run_manifest_hash", receipt.agentRunManifestHash());
        requireText(
                row,
                "isolated_domain_db_binding_hash",
                receipt.isolatedDomainDbBindingHash());
        requireText(row, "receipt_hash", receipt.receiptHash());
        requireText(row, "formal_writer", receipt.formalWriter().name());
        requireText(row, "domain_commit_status", receipt.domainCommitStatus().name());
        requireInstant(row, "committed_at", receipt.committedAt());
        requirePresent(row, "recorded_at");

        if (!requiredText(activation, "activation_id").equals(receipt.activationId())
                || !requiredText(activation, "execution_lane")
                        .equals(receipt.executionLane())
                || !requiredText(activation, "isolated_domain_db_binding_hash")
                        .equals(receipt.isolatedDomainDbBindingHash())
                || !evidence.tenantSurrogate().equals(receipt.tenantSurrogate())
                || !evidence.caseId().equals(receipt.caseId())
                || receipt.roomType() != RoomType.INTAKE
                || evidence.roomEpoch() != receipt.roomEpoch()
                || evidence.fencingToken() != receipt.roomFencingToken()
                || evidence.receipt().processRevision() != receipt.processRevision()
                || winner.command().stageSequence() != receipt.stageSequence()
                || !requiredText(run, "id").equals(receipt.logicalRunId())
                || !requiredText(winner.attempt(), "id").equals(receipt.attemptId())
                || !winner.envelope().commandHash().equals(receipt.commandHash())
                || !winner.envelope()
                        .commandEnvelopeHash()
                        .equals(receipt.commandEnvelopeHash())
                || !winner.command().graphKey().equals(receipt.graphKey())
                || !winner.command().graphVersion().equals(receipt.graphVersion())
                || !winner.command()
                        .checkpointSchemaVersion()
                        .equals(receipt.checkpointSchemaVersion())
                || !requiredText(winner.attempt(), "checkpoint_id")
                        .equals(receipt.checkpointId())
                || !evidence.receipt().resultHash().equals(receipt.resultHash())
                || !requiredText(run, "committed_manifest_id")
                        .equals(receipt.agentRunManifestId())
                || !requiredText(run, "committed_manifest_hash")
                        .equals(receipt.agentRunManifestHash())) {
            throw new IllegalArgumentException(
                    "target receipt differs from recovered winner authority");
        }
    }

    private void validateRecoveredManifest(
            CompletionEvidence evidence,
            JsonNode activation,
            JsonNode run,
            ValidatedRecoveredAttempt winner,
            JsonNode manifest,
            JsonNode outputSnapshot,
            ProductionFinalizationReceipt targetReceipt) {
        RoomGraphCommand command = winner.command();
        requireText(manifest, "schema_version", "agent-execution-manifest.v1");
        requireText(manifest, "id", requiredText(run, "committed_manifest_id"));
        requireText(
                manifest,
                "manifest_sha256",
                requiredText(run, "committed_manifest_hash"));
        requireText(
                manifest, "manifest_sha256", targetReceipt.agentRunManifestHash());
        requireText(manifest, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(manifest, "case_id", evidence.caseId());
        requireText(manifest, "room_type", "INTAKE");
        requireLong(manifest, "room_epoch", evidence.roomEpoch());
        requireLong(
                manifest, "process_revision", evidence.receipt().processRevision());
        requireLong(manifest, "fencing_token", evidence.fencingToken());
        requireText(manifest, "logical_agent_run_id", requiredText(run, "id"));
        requireText(
                manifest, "attempt_id", requiredText(winner.attempt(), "id"));
        validateRecoveredManifestWorkflow(manifest, activation, run);
        requireText(manifest, "graph_key", command.graphKey());
        requireText(manifest, "graph_version", command.graphVersion());
        requireText(
                manifest,
                "checkpoint_schema_version",
                command.checkpointSchemaVersion());
        requireText(
                manifest,
                "checkpoint_id",
                requiredText(winner.attempt(), "checkpoint_id"));
        requireText(
                manifest,
                "prompt_version",
                command.invocationContext().promptProfileId());
        requireText(
                manifest,
                "model_profile_id",
                command.invocationContext().modelProfileId());
        requireText(
                manifest,
                "provider",
                requiredText(winner.attempt(), "provider"));
        requireText(
                manifest,
                "model_version",
                requiredText(winner.attempt(), "model_version"));
        requireText(
                manifest,
                "policy_version",
                command.invocationContext().policyVersion());
        requireText(
                manifest,
                "guardrail_version",
                command.invocationContext().guardrailVersion());
        requireText(manifest, "output_sha256", evidence.receipt().resultHash());
        requireText(manifest, "traceparent", command.traceparent());
        requireText(manifest, "terminal_status", "COMPLETED");
        requirePresent(manifest, "finalized_at");

        requireText(
                outputSnapshot,
                "id",
                requiredText(manifest, "output_snapshot_id"));
        requireText(
                outputSnapshot, "tenant_surrogate", evidence.tenantSurrogate());
        requireText(outputSnapshot, "case_id", evidence.caseId());
        requireText(outputSnapshot, "room_type", "INTAKE");
        requireText(outputSnapshot, "snapshot_type", "AGENT_OUTPUT");
        requireText(outputSnapshot, "source_type", "AGENT_RUN");
        requireText(outputSnapshot, "source_id", requiredText(run, "id"));
        requireText(outputSnapshot, "schema_version", "room-graph-result.v1");
        requireText(
                outputSnapshot,
                "content_sha256",
                evidence.receipt().resultHash());
    }

    private static void validateRecoveredManifestWorkflow(
            JsonNode manifest, JsonNode activation, JsonNode run) {
        requireText(manifest, "workflow_type", AgentRunWorkflow.WORKFLOW_TYPE);
        requireText(
                manifest,
                "workflow_id",
                TemporalAgentRunV2WorkflowLauncher.workflowId(requiredText(run, "id")));
        requireNonBlank(manifest, "workflow_run_id");
        requireText(
                manifest,
                "workflow_build_id",
                requiredText(activation, "agent_build_id"));
    }

    private void validateRecoveredFormalEvent(
            CompletionEvidence evidence,
            JsonNode formalOperation,
            JsonNode formalEvent,
            RoomGraphCommand winnerCommand,
            ProductionFinalizationReceipt targetReceipt,
            IntakeFinalizationReceipt formalReceipt) {
        requireSha256(formalOperation, "request_hash");
        String formalRequestHash = requiredText(formalOperation, "request_hash");
        JsonNode event = requiredObject(formalEvent, "event_json");
        requireExactFields(
                event,
                Set.of(
                        "schema_version",
                        "event_type",
                        "operation_key",
                        "request_hash",
                        "result_hash",
                        "proposal_hash",
                        "message_id",
                        "actor_scope_hash",
                        "receipt"),
                "formal Intake event");
        requireText(event, "schema_version", "intake-turn-committed-event.v1");
        requireText(event, "event_type", evidence.eventType());
        requireText(event, "operation_key", evidence.formalOperationKey());
        requireText(event, "request_hash", formalRequestHash);
        requireText(event, "result_hash", targetReceipt.resultHash());
        requireText(event, "proposal_hash", formalReceipt.proposalHash());
        requireText(event, "message_id", formalReceipt.formalMessageId());
        requireText(event, "actor_scope_hash", formalReceipt.actorScopeHash());
        if (!canonicalJsonEquals(
                        objectMapper.valueToTree(formalReceipt), event.required("receipt"))
                || !formalReceipt.commandId().equals(winnerCommand.commandId())
                || !formalReceipt.logicalRunId().equals(targetReceipt.logicalRunId())
                || !formalReceipt.attemptId().equals(targetReceipt.attemptId())
                || !formalReceipt.resultHash().equals(targetReceipt.resultHash())) {
            throw new IllegalArgumentException(
                    "formal Intake event differs from the recovered winning receipt");
        }
    }

    private JsonNode readProofObject(String raw, String label) {
        JsonNode value = readProofJson(raw, label);
        if (!value.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value;
    }

    private List<JsonNode> readProofArray(String raw, String label) {
        JsonNode value = readProofJson(raw, label);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be a non-empty array");
        }
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return List.copyOf(result);
    }

    private JsonNode readProofJson(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            if (value == null) {
                throw new IllegalArgumentException(label + " is empty");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(label + " is invalid JSON", failure);
        }
    }

    private static <T> T treeToValue(
            ObjectMapper mapper, JsonNode value, Class<T> type, String label) {
        try {
            return mapper.treeToValue(value, type);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(label + " is invalid", failure);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be null or non-blank text");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!Objects.equals(requiredText(parent, field), expected)) {
            throw new IllegalArgumentException(field + " differs from canonical authority");
        }
    }

    private static void requireLong(JsonNode parent, String field, long expected) {
        if (requiredLong(parent, field) != expected) {
            throw new IllegalArgumentException(field + " differs from canonical authority");
        }
    }

    private static void requireNonBlank(JsonNode parent, String field) {
        requiredText(parent, field);
    }

    private static void requireSha256(JsonNode parent, String field) {
        if (!requiredText(parent, field).matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    private static boolean hasPresent(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && !value.isNull();
    }

    private static void requirePresent(JsonNode parent, String field) {
        if (!hasPresent(parent, field)) {
            throw new IllegalArgumentException(field + " is missing");
        }
    }

    private static void requireInstant(JsonNode parent, String field, Instant expected) {
        try {
            Instant actual = OffsetDateTime.parse(requiredText(parent, field)).toInstant();
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException(
                        field + " differs from canonical authority");
            }
        } catch (java.time.DateTimeException failure) {
            throw new IllegalArgumentException(field + " is not an exact timestamp", failure);
        }
    }

    private static void requireExactFields(
            JsonNode parent, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        parent.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(label + " members are not exact");
        }
    }

    private static boolean sameNullableText(String left, String right) {
        return Objects.equals(left, right);
    }

    private static boolean canonicalJsonEquals(JsonNode left, JsonNode right) {
        return ContractJson.canonicalString(left).equals(ContractJson.canonicalString(right));
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

    private enum CompletionAuthorityKind {
        LEGACY_EXACT_COMMAND,
        TARGET_RECOVERED_WINNER_V1
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
            IntakeFinalizationReceipt receipt,
            CompletionAuthorityKind authorityKind,
            RecoveredWinnerProof recoveredWinnerProof) {}

    private record RecoveredWinnerProof(
            String candidateJson, byte[] targetReceiptCanonicalBytes, String attemptsJson) {

        private RecoveredWinnerProof {
            targetReceiptCanonicalBytes =
                    targetReceiptCanonicalBytes == null
                            ? null
                            : targetReceiptCanonicalBytes.clone();
        }

        @Override
        public byte[] targetReceiptCanonicalBytes() {
            return targetReceiptCanonicalBytes == null
                    ? null
                    : targetReceiptCanonicalBytes.clone();
        }
    }

    private record ValidatedRecoveredAttempt(
            JsonNode attempt,
            JsonNode admission,
            JsonNode material,
            IntakeCommandExecutionContext context,
            IntakeTargetAgentRunContext target,
            ExecuteAgentRunRequest request,
            RoomGraphCommand command,
            ProductionGraphCommandEnvelope envelope) {}

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
