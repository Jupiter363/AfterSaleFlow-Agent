package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptCodec;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.FormalFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult.FinalizationLocator;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Target-only, read-only recovery port for the Intake AgentRun child.
 *
 * <p>A completed response is accepted only when the immutable target receipt, admitted-command
 * completion, normal Intake operation ledger, and normal Intake receipt event agree in one
 * repeatable-read snapshot. This is deliberately an Activity-sized single lookup: Temporal owns
 * retry and scheduling; this adapter never polls.
 */
public final class JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
        implements IntakeAgentRunFinalizationReceiptReadPort {

    private static final String TARGET_RECEIPT_SQL = """
            select receipt.receipt_id, receipt.activation_manifest_hash,
                   receipt.receipt_canonical_bytes,
                   receipt.schema_version, receipt.execution_lane, receipt.activation_id,
                   receipt.tenant_surrogate, receipt.case_id, receipt.room_type,
                   receipt.room_epoch, receipt.room_fencing_token,
                   receipt.process_revision, receipt.stage_sequence,
                   receipt.logical_run_id, receipt.attempt_id, receipt.command_hash,
                   receipt.command_envelope_hash, receipt.graph_key, receipt.graph_version,
                   receipt.checkpoint_schema_version, receipt.checkpoint_id,
                   receipt.result_hash, receipt.proposal_hash, receipt.result_envelope_hash,
                   receipt.agent_run_manifest_id, receipt.agent_run_manifest_hash,
                   receipt.isolated_domain_db_binding_hash, receipt.committed_at,
                   receipt.receipt_hash, receipt.formal_writer, receipt.domain_commit_status,
                   attempt.command_id as attempt_command_id,
                   attempt.command_request_hash as attempt_request_hash,
                   cast(attempt.command_json as text) as attempt_command_json,
                   attempt.attempt_no, attempt.logical_input_hash,
                   attempt.previous_attempt_id, attempt.reset_required,
                   attempt.public_sequence_offset, run.attempt_limit,
                   material.context_canonical_json, material.context_sha256
              from target_e2e_finalization_receipt receipt
              join agent_run run
                on run.id = receipt.logical_run_id
               and run.protocol = 'agent-stream.v2'
               and run.executor_kind = 'TEMPORAL_ACTIVITY'
               and run.committed_attempt_id = receipt.attempt_id
               and run.final_result_hash = receipt.result_hash
               and run.finalization_status = 'COMMITTED'
              join agent_run_attempt attempt
                on attempt.agent_run_id = run.id
               and attempt.id = receipt.attempt_id
               and attempt.attempt_status = 'COMPLETED'
               and attempt.result_hash = receipt.result_hash
               and attempt.logical_input_hash = run.logical_input_hash
              join target_e2e_command_admission admission
                on admission.activation_id = receipt.activation_id
               and admission.activation_manifest_hash = receipt.activation_manifest_hash
               and admission.execution_lane = receipt.execution_lane
               and admission.tenant_surrogate = receipt.tenant_surrogate
               and admission.case_id = receipt.case_id
               and admission.command_id = attempt.command_id
               and admission.command_hash = receipt.command_hash
               and admission.command_envelope_hash = receipt.command_envelope_hash
               and admission.room_epoch = receipt.room_epoch
               and admission.room_fencing_token = receipt.room_fencing_token
              join target_e2e_intake_command_material material
                on material.admission_id = admission.admission_id
               and material.activation_id = admission.activation_id
               and material.activation_manifest_hash = admission.activation_manifest_hash
               and material.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
               and material.tenant_surrogate = admission.tenant_surrogate
               and material.case_id = admission.case_id
               and material.command_id = admission.command_id
               and material.command_hash = admission.command_hash
               and material.command_envelope_hash = admission.command_envelope_hash
               and material.room_epoch = admission.room_epoch
               and material.room_fencing_token = admission.room_fencing_token
             where receipt.activation_id = :activationId
               and receipt.activation_manifest_hash = :activationManifestHash
               and receipt.execution_lane = 'TARGET_E2E_CANDIDATE'
               and receipt.tenant_surrogate = :tenantSurrogate
               and receipt.case_id = :caseId
               and receipt.room_type = 'INTAKE'
               and receipt.room_epoch = :roomEpoch
               and receipt.room_fencing_token = :roomFencingToken
               and receipt.process_revision = :processRevision
               and receipt.logical_run_id = :logicalRunId
            """;

    private static final String COMPLETION_SQL = """
            select completion_hash
              from target_e2e_command_completion completion
              join target_e2e_command_admission admission
                on admission.admission_id = completion.admission_id
             where completion.activation_id = :activationId
               and completion.command_id = :commandId
               and completion.command_hash = :commandHash
               and completion.command_envelope_hash = :commandEnvelopeHash
               and admission.activation_manifest_hash = :activationManifestHash
               and admission.execution_lane = 'TARGET_E2E_CANDIDATE'
               and admission.tenant_surrogate = :tenantSurrogate
               and admission.case_id = :caseId
               and admission.room_epoch = :roomEpoch
               and admission.room_fencing_token = :roomFencingToken
            """;

    private static final String OPERATION_SQL = """
            select request_hash, result_uri, result_sha256, operation_status,
                   case_id, room_epoch, process_revision, fencing_token
              from domain_operation
             where tenant_surrogate = :tenantSurrogate
               and operation_key = :operationKey
            """;

    private static final String EVENT_SQL = """
            select id, case_id, sequence_no, event_type, event_json::text as event_json
              from case_timeline_event
             where id = :eventId
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final TargetE2EGraphEnvelopeCodec envelopes;

    public JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this(new NamedParameterJdbcTemplate(dataSource), transactionManager, objectMapper);
    }

    public JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
            NamedParameterJdbcOperations jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = targetMaterialObjectMapper(objectMapper);
        this.envelopes = new TargetE2EGraphEnvelopeCodec(objectMapper);
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setReadOnly(true);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    static ObjectMapper targetMaterialObjectMapper(ObjectMapper objectMapper) {
        return Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                // V049 stores the unannotated Intake execution envelope as camelCase.
                // Explicitly annotated nested graph and receipt contracts remain snake_case.
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    @Override
    public IntakeAgentRunFinalizationReadResult read(IntakeAgentRunFinalizationReadRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            IntakeAgentRunFinalizationReadResult result =
                    transactions.execute(ignored -> readInTransaction(request));
            if (result == null) {
                throw rejected("TARGET_E2E_FINALIZATION_READ_EMPTY", "receipt read returned no result");
            }
            return result;
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            if (failure instanceof TransientDataAccessException
                    || failure instanceof RecoverableDataAccessException
                    || failure instanceof DataAccessResourceFailureException) {
                throw new TargetE2eFinalizationReadPersistenceException(
                        "target Intake finalization read has a retryable database failure", failure);
            }
            throw rejected(
                    "TARGET_E2E_FINALIZATION_READ_PERSISTENCE_INVARIANT",
                    "target Intake finalization read violated a database invariant", failure);
        }
    }

    private IntakeAgentRunFinalizationReadResult readInTransaction(
            IntakeAgentRunFinalizationReadRequest request) {
        var command = request.command();
        var target = command.executionContext().targetAgentRun();
        var child = request.childState();
        Map<String, Object> parameters = Map.ofEntries(
                Map.entry("activationId", target.activationId()),
                Map.entry("activationManifestHash", target.activationManifestHash()),
                Map.entry("tenantSurrogate", command.tenantSurrogate()),
                Map.entry("caseId", command.caseId()),
                Map.entry("roomEpoch", command.roomEpoch()),
                Map.entry("roomFencingToken", command.fencingToken()),
                Map.entry("processRevision", target.expectedProcessRevision()),
                Map.entry("logicalRunId", child.logicalRunId()));
        List<TargetRow> rows = jdbc.query(TARGET_RECEIPT_SQL, parameters, this::targetRow);
        if (rows.size() > 1) {
            throw rejected("TARGET_E2E_FINALIZATION_READ_AMBIGUOUS", "target receipt identity is not unique");
        }
        if (rows.isEmpty()) {
            return unresolved(request);
        }

        TargetRow row = rows.getFirst();
        TargetE2eFinalizationReceipt receipt = decodeTargetReceipt(row);
        requireTargetBinding(request, receipt, row);
        requireSingleCompletion(parameters, receipt, row);
        FormalProjection formal = readFormalProjection(request, receipt, row);
        return new IntakeAgentRunFinalizationReadResult(
                "intake-agent-run-finalization-read-result.v1",
                IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED,
                locator(receipt, row.activationManifestHash(), formal.formal()),
                formal.toActivityReceipt(
                        command.requestHash(), command.party(), receipt.checkpointId()));
    }

    private IntakeAgentRunFinalizationReadResult unresolved(
            IntakeAgentRunFinalizationReadRequest request) {
        if (request.childState().status()
                == com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunChildState.Status.TERMINAL_NO_COMMIT) {
            return new IntakeAgentRunFinalizationReadResult(
                    "intake-agent-run-finalization-read-result.v1",
                    IntakeAgentRunFinalizationReadResult.Resolution.ABSENT_TERMINAL,
                    null,
                    null);
        }
        return new IntakeAgentRunFinalizationReadResult(
                "intake-agent-run-finalization-read-result.v1",
                IntakeAgentRunFinalizationReadResult.Resolution.PENDING,
                null,
                null);
    }

    private void requireSingleCompletion(
            Map<String, Object> parameters,
            TargetE2eFinalizationReceipt receipt,
            TargetRow targetRow) {
        Map<String, Object> completionParameters = new java.util.HashMap<>(parameters);
        completionParameters.put("commandId", targetRow.attemptCommandId());
        completionParameters.put("commandHash", receipt.commandHash());
        completionParameters.put("commandEnvelopeHash", receipt.commandEnvelopeHash());
        List<String> rows = jdbc.queryForList(COMPLETION_SQL, completionParameters, String.class);
        if (rows.size() != 1) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_COMPLETION_MISSING",
                    "target receipt is not atomically paired with one admitted command completion");
        }
        requireCanonicalCompletionHash(rows.getFirst(), receipt.receiptHash());
    }

    static void requireCanonicalCompletionHash(
            String persistedCompletionHash, String canonicalReceiptHash) {
        if (!Objects.equals(canonicalReceiptHash, persistedCompletionHash)) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_COMPLETION_HASH_MISMATCH",
                    "target command completion hash is not canonical");
        }
    }

    private FormalProjection readFormalProjection(
            IntakeAgentRunFinalizationReadRequest request,
            TargetE2eFinalizationReceipt targetReceipt,
            TargetRow targetRow) {
        var command = request.command();
        String operationKey = IntakeOperationKeys.turnFinalize(
                command.caseId(), command.roomEpoch(), command.executionContext().threadId(),
                targetRow.attemptCommandId(), targetReceipt.resultHash());
        List<OperationRow> operations = jdbc.query(
                OPERATION_SQL,
                Map.of("tenantSurrogate", command.tenantSurrogate(), "operationKey", operationKey),
                this::operationRow);
        if (operations.size() != 1) {
            throw rejected("TARGET_E2E_FINALIZATION_OPERATION_MISSING", "formal Intake operation is absent");
        }
        OperationRow operation = operations.getFirst();
        if (!"COMPLETED".equals(operation.status())
                || !command.caseId().equals(operation.caseId())
                || command.roomEpoch() != operation.roomEpoch()
                || targetReceipt.processRevision() != operation.processRevision()
                || command.fencingToken() != operation.fencingToken()
                || operation.resultUri() == null
                || !operation.resultUri().startsWith("urn:intake:finalization-receipt:")) {
            throw rejected("TARGET_E2E_FINALIZATION_OPERATION_MISMATCH", "formal Intake operation conflicts with target receipt");
        }
        String eventId = operation.resultUri().substring("urn:intake:finalization-receipt:".length());
        List<EventRow> events = jdbc.query(EVENT_SQL, Map.of("eventId", eventId), this::eventRow);
        if (events.size() != 1) {
            throw rejected("TARGET_E2E_FINALIZATION_EVENT_MISSING", "formal Intake receipt event is absent");
        }
        FormalProjection formal =
                decodeFormalProjection(
                        request, targetReceipt, targetRow, operation, events.getFirst());
        requireCanonicalFormalOperationHash(
                operation.resultHash(), formal.formal().receiptHash());
        return formal;
    }

    static void requireCanonicalFormalOperationHash(
            String persistedOperationHash, String canonicalFormalReceiptHash) {
        if (!Objects.equals(canonicalFormalReceiptHash, persistedOperationHash)) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_OPERATION_HASH_MISMATCH",
                    "formal Intake operation hash is not canonical");
        }
    }

    private FormalProjection decodeFormalProjection(
            IntakeAgentRunFinalizationReadRequest request,
            TargetE2eFinalizationReceipt targetReceipt,
            TargetRow targetRow,
            OperationRow operation,
            EventRow event) {
        try {
            JsonNode document = objectMapper.readTree(event.eventJson());
            if (!"intake-turn-committed-event.v1".equals(document.path("schema_version").asText())
                    || !operation.requestHash().equals(document.path("request_hash").asText())
                    || !targetReceipt.resultHash().equals(document.path("result_hash").asText())
                    || !event.id().equals(document.path("receipt").path("domain_event_ids").get(0).asText())) {
                throw rejected("TARGET_E2E_FINALIZATION_EVENT_MISMATCH", "formal Intake event conflicts with its receipt");
            }
            IntakeFinalizationReceipt formal = objectMapper.treeToValue(
                    document.required("receipt"), IntakeFinalizationReceipt.class);
            formal.requireCanonicalHash();
            requireFormalEventProposalHash(
                    formal.proposalHash(), document.path("proposal_hash").asText());
            IntakeDomainEventType eventType = IntakeDomainEventType.valueOf(event.eventType());
            if (eventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                    && eventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
                throw rejected("TARGET_E2E_FINALIZATION_EVENT_TYPE_INVALID", "formal Intake event is not a turn event");
            }
            requireFormalBinding(request, targetReceipt, targetRow, formal, event);
            return new FormalProjection(formal, event, operation.requestHash());
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw rejected("TARGET_E2E_FINALIZATION_EVENT_INVALID", "formal Intake event cannot be decoded", failure);
        }
    }

    private void requireTargetBinding(
            IntakeAgentRunFinalizationReadRequest request,
            TargetE2eFinalizationReceipt receipt,
            TargetRow row) {
        var command = request.command();
        var target = command.executionContext().targetAgentRun();
        var child = request.childState();
        RoomGraphCommand winningCommand = decodeAttemptCommand(row);
        ExecuteAgentRunRequest winningRequest = decodeWinningMaterial(row, receipt, winningCommand);
        if (!receipt.activationId().equals(target.activationId())
                || !row.activationManifestHash().equals(target.activationManifestHash())
                || !receipt.tenantSurrogate().equals(command.tenantSurrogate())
                || !receipt.caseId().equals(command.caseId())
                || receipt.roomEpoch() != command.roomEpoch()
                || receipt.roomFencingToken() != command.fencingToken()
                || receipt.processRevision() != target.expectedProcessRevision()
                || !receipt.logicalRunId().equals(child.logicalRunId())
                || (child.resultHash() != null
                    && !receipt.resultHash().equals(child.resultHash()))
                || !winningCommand.commandId().equals(row.attemptCommandId())
                || !winningCommand.requestHash().equals(row.attemptRequestHash())
                || !winningRequest.command().equals(winningCommand)
                || winningRequest.attemptNo() < target.request().attemptNo()
                || winningRequest.attemptLimit() != target.request().attemptLimit()
                || !winningRequest.logicalInputHash().equals(
                    target.request().logicalInputHash())
                || (winningRequest.attemptNo() == target.request().attemptNo()
                    && !winningRequest.attemptId().equals(target.request().attemptId()))
                || (winningRequest.attemptNo() > target.request().attemptNo()
                    && (winningRequest.attemptId().equals(target.request().attemptId())
                        || winningRequest.command().commandId().equals(
                            target.request().command().commandId())))
                || !winningCommand.logicalRunId().equals(receipt.logicalRunId())
                || !winningCommand.attemptId().equals(receipt.attemptId())
                || !winningCommand.tenantSurrogate().equals(receipt.tenantSurrogate())
                || !winningCommand.caseId().equals(receipt.caseId())
                || winningCommand.roomEpoch() != receipt.roomEpoch()
                || !winningCommand.graphKey().equals(receipt.graphKey())
                || !winningCommand.graphVersion().equals(receipt.graphVersion())
                || !winningCommand.checkpointSchemaVersion().equals(receipt.checkpointSchemaVersion())
                || !receipt.resultHash().equals(child.resultHash() == null ? receipt.resultHash() : child.resultHash())
                || !receipt.agentRunManifestId().equals(row.agentRunManifestId())
                || !receipt.agentRunManifestHash().equals(row.agentRunManifestHash())
                || !receipt.isolatedDomainDbBindingHash().equals(row.isolatedDomainDbBindingHash())) {
            throw rejected("TARGET_E2E_FINALIZATION_RECEIPT_MISMATCH", "target receipt is outside the exact Intake child authority");
        }
    }

    private void requireFormalBinding(
            IntakeAgentRunFinalizationReadRequest request,
            TargetE2eFinalizationReceipt targetReceipt,
            TargetRow targetRow,
            IntakeFinalizationReceipt formal,
            EventRow event) {
        var command = request.command();
        var target = command.executionContext().targetAgentRun();
        if (!formal.tenantSurrogate().equals(command.tenantSurrogate())
                || !formal.caseId().equals(command.caseId())
                || formal.roomEpoch() != command.roomEpoch()
                || !formal.threadId().equals(command.executionContext().threadId())
                || !formal.actorScopeHash().equals(command.actorScopeHash())
                || !formal.agentSessionId().equals(command.executionContext().agentSessionId())
                || !formal.commandId().equals(targetRow.attemptCommandId())
                || !formal.logicalRunId().equals(targetReceipt.logicalRunId())
                || !formal.attemptId().equals(targetReceipt.attemptId())
                || !formal.resultHash().equals(targetReceipt.resultHash())
                || formal.processRevision() != target.expectedProcessRevision()
                || formal.roomRevision() != target.expectedRoomRevision()
                || formal.fencingToken() != command.fencingToken()
                || event.caseId() == null
                || !event.caseId().equals(command.caseId())) {
            throw rejected("TARGET_E2E_FINALIZATION_FORMAL_RECEIPT_MISMATCH", "formal Intake receipt is outside the exact child authority");
        }
    }

    private TargetE2eFinalizationReceipt decodeTargetReceipt(TargetRow row) {
        TargetE2eFinalizationReceipt decoded = TargetE2eFinalizationReceiptCodec.decodeCanonical(row.canonicalBytes());
        if (!decoded.equals(row.columns())) {
            throw rejected("TARGET_E2E_FINALIZATION_RECEIPT_BYTES_MISMATCH", "receipt columns differ from canonical receipt bytes");
        }
        return decoded;
    }

    private RoomGraphCommand decodeAttemptCommand(TargetRow row) {
        return decodeAttemptCommand(
                objectMapper, row.attemptCommandJson(), row.attemptRequestHash());
    }

    static RoomGraphCommand decodeAttemptCommand(
            ObjectMapper objectMapper,
            String persistedCommandJson,
            String persistedRequestHash) {
        try {
            JsonNode document = objectMapper.readTree(persistedCommandJson);
            if (document == null || !document.isObject()) {
                throw rejected(
                        "TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID",
                        "winning attempt command is not an object");
            }
            RoomGraphCommand command = objectMapper.treeToValue(document, RoomGraphCommand.class);
            com.fasterxml.jackson.databind.node.ObjectNode unhashed =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) document).deepCopy();
            unhashed.remove("request_hash");
            if (!persistedRequestHash.equals(ContractJson.sha256Hex(unhashed))) {
                throw rejected(
                        "TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID",
                        "winning attempt command self-hash is invalid");
            }
            return command;
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID",
                    "winning attempt command cannot be decoded",
                    failure);
        }
    }

    private ExecuteAgentRunRequest decodeWinningMaterial(
            TargetRow row,
            TargetE2eFinalizationReceipt receipt,
            RoomGraphCommand winningCommand) {
        try {
            JsonNode materialDocument = objectMapper.readTree(row.materialCanonicalJson());
            if (materialDocument == null
                    || !row.materialCanonicalJson().equals(
                            ContractJson.canonicalString(materialDocument))
                    || !row.materialSha256().equals(ContractJson.sha256Hex(materialDocument))) {
                throw rejected(
                        "TARGET_E2E_FINALIZATION_WINNING_MATERIAL_INVALID",
                        "winning Intake material is not canonical");
            }
            IntakeCommandExecutionContext material = objectMapper.treeToValue(
                    materialDocument, IntakeCommandExecutionContext.class);
            ExecuteAgentRunRequest expected = new ExecuteAgentRunRequest(
                    ExecuteAgentRunRequest.SCHEMA_VERSION,
                    receipt.logicalRunId(),
                    row.attemptNo(),
                    row.attemptLimit(),
                    "agent-stream.v2",
                    row.logicalInputHash(),
                    row.previousAttemptId(),
                    row.resetRequired(),
                    row.publicSequenceOffset(),
                    winningCommand);
            if (!expected.equals(material.targetAgentRun().request())) {
                throw rejected(
                        "TARGET_E2E_FINALIZATION_WINNING_MATERIAL_INVALID",
                        "winning Intake material does not bind the committed attempt");
            }

            var sealed = envelopes.wrapCommand(
                    receipt.activationId(), receipt.roomFencingToken(), winningCommand);
            String commandHash = sealed.commandHash();
            String commandEnvelopeHash = sealed.commandEnvelopeHash();
            if (!receipt.commandHash().equals(commandHash)
                    || !receipt.commandEnvelopeHash().equals(commandEnvelopeHash)
                    || !material.targetAgentRun().activationId().equals(receipt.activationId())
                    || material.targetAgentRun().roomFencingToken()
                            != receipt.roomFencingToken()
                    || !material.targetAgentRun().commandHash().equals(commandHash)
                    || !material.targetAgentRun().commandEnvelopeHash().equals(
                            commandEnvelopeHash)) {
                throw rejected(
                        "TARGET_E2E_FINALIZATION_WINNING_MATERIAL_INVALID",
                        "winning Intake command envelope differs from its material");
            }
            return expected;
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_WINNING_MATERIAL_INVALID",
                    "winning Intake material cannot be decoded",
                    failure);
        }
    }

    private static FinalizationLocator locator(
            TargetE2eFinalizationReceipt receipt,
            String manifestHash,
            IntakeFinalizationReceipt formal) {
        return new FinalizationLocator(
                "intake-agent-run-finalization-locator.v1", receipt.executionLane(),
                receipt.activationId(), manifestHash, receipt.roomFencingToken(),
                receipt.logicalRunId(), receipt.attemptId(), receipt.resultHash(), formal.proposalHash(),
                receipt.checkpointId(), formal.operationKey(),
                receipt.agentRunManifestId(), receipt.agentRunManifestHash(),
                receipt.isolatedDomainDbBindingHash(), receipt.receiptHash());
    }

    private TargetRow targetRow(ResultSet rs, int ignored) throws SQLException {
        TargetE2eFinalizationReceipt columns = new TargetE2eFinalizationReceipt(
                rs.getString("schema_version"), rs.getString("execution_lane"), rs.getString("activation_id"),
                rs.getString("tenant_surrogate"), rs.getString("case_id"),
                com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.valueOf(rs.getString("room_type")),
                rs.getLong("room_epoch"), rs.getLong("room_fencing_token"), rs.getLong("process_revision"),
                rs.getLong("stage_sequence"), rs.getString("logical_run_id"), rs.getString("attempt_id"),
                rs.getString("command_hash"), rs.getString("command_envelope_hash"), rs.getString("graph_key"),
                rs.getString("graph_version"), rs.getString("checkpoint_schema_version"), rs.getString("checkpoint_id"),
                rs.getString("result_hash"), rs.getString("proposal_hash"), rs.getString("result_envelope_hash"),
                rs.getString("agent_run_manifest_id"), rs.getString("agent_run_manifest_hash"),
                rs.getString("isolated_domain_db_binding_hash"), rs.getObject("committed_at", OffsetDateTime.class).toInstant(),
                rs.getString("receipt_hash"), TargetE2eFinalizationReceipt.FormalWriter.valueOf(rs.getString("formal_writer")),
                TargetE2eFinalizationReceipt.DomainCommitStatus.valueOf(rs.getString("domain_commit_status")));
        return new TargetRow(rs.getString("receipt_id"), rs.getString("activation_manifest_hash"),
                rs.getBytes("receipt_canonical_bytes"), columns, columns.agentRunManifestId(),
                columns.agentRunManifestHash(), columns.isolatedDomainDbBindingHash(),
                rs.getString("attempt_command_id"), rs.getString("attempt_request_hash"),
                rs.getString("attempt_command_json"), rs.getLong("attempt_no"),
                rs.getInt("attempt_limit"), rs.getString("logical_input_hash"),
                rs.getString("previous_attempt_id"), rs.getBoolean("reset_required"),
                rs.getInt("public_sequence_offset"), rs.getString("context_canonical_json"),
                rs.getString("context_sha256"));
    }

    private OperationRow operationRow(ResultSet rs, int ignored) throws SQLException {
        return new OperationRow(rs.getString("request_hash"), rs.getString("result_uri"),
                rs.getString("result_sha256"), rs.getString("operation_status"), rs.getString("case_id"),
                rs.getLong("room_epoch"), rs.getLong("process_revision"), rs.getLong("fencing_token"));
    }

    private EventRow eventRow(ResultSet rs, int ignored) throws SQLException {
        return new EventRow(rs.getString("id"), rs.getString("case_id"), rs.getLong("sequence_no"),
                rs.getString("event_type"), rs.getString("event_json"));
    }

    private static TargetE2eFinalizationRejectedException rejected(String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }

    private static TargetE2eFinalizationRejectedException rejected(String code, String message, Throwable cause) {
        return new TargetE2eFinalizationRejectedException(code, message, cause);
    }

    /**
     * The target receipt binds the outer result-envelope proposal descriptor, while the formal
     * Intake receipt binds the persisted proposal payload. Those hashes are independent domains;
     * the formal event must repeat the latter.
     */
    static void requireFormalEventProposalHash(
            String formalPayloadProposalHash, String eventProposalHash) {
        if (!Objects.equals(formalPayloadProposalHash, eventProposalHash)) {
            throw rejected(
                    "TARGET_E2E_FINALIZATION_EVENT_MISMATCH",
                    "formal Intake event conflicts with its receipt");
        }
    }

    private record TargetRow(String receiptId, String activationManifestHash, byte[] canonicalBytes,
            TargetE2eFinalizationReceipt columns, String agentRunManifestId, String agentRunManifestHash,
            String isolatedDomainDbBindingHash, String attemptCommandId, String attemptRequestHash,
            String attemptCommandJson, long attemptNo, int attemptLimit, String logicalInputHash,
            String previousAttemptId, boolean resetRequired, int publicSequenceOffset,
            String materialCanonicalJson, String materialSha256) {}
    private record OperationRow(String requestHash, String resultUri, String resultHash, String status,
            String caseId, long roomEpoch, long processRevision, long fencingToken) {}
    private record EventRow(String id, String caseId, long sequence, String eventType, String eventJson) {}

    private record FormalProjection(IntakeFinalizationReceipt formal, EventRow event, String formalRequestHash) {
        TurnFinalizationReceipt toActivityReceipt(
                String workflowCommandRequestHash,
                com.example.dispute.workflow.temporal.room.intake.IntakeParty party,
                String checkpointId) {
            IntakeDomainEventType eventType = IntakeDomainEventType.valueOf(event.eventType());
            IntakeAgentRunRef agentRun = new IntakeAgentRunRef("intake-agent-run-ref.v1", formal.logicalRunId(),
                    formal.attemptId(), formal.resultHash());
            IntakeGraphExecutionRef graph = new IntakeGraphExecutionRef("intake-graph-execution-ref.v1", formal.threadId(),
                    formal.commandId(), "intake.v2", "target-e2e-graph.2026-07-27.1", checkpointId,
                    "urn:target-e2e:result:intake:" + formal.resultHash(), formal.resultHash(),
                    "urn:target-e2e:proposal:intake:" + formal.proposalHash(), formal.proposalHash());
            IntakeDomainEventRef committed = new IntakeDomainEventRef("intake-domain-event-ref.v1", event.id(),
                    "urn:target-e2e:intake-finalization:" + formal.receiptHash(),
                    ContractJson.sha256Hex(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(event.eventJson())),
                    event.sequence(), eventType, party,
                    formal.commandId(), formal.tenantSurrogate(), formal.caseId(), formal.roomEpoch(), formal.fencingToken(),
                    formal.actorScopeHash(), formal.operationKey(), workflowCommandRequestHash, formal.resultHash(),
                    formal.processRevision(), formal.roomRevision(), agentRun, graph);
            FormalFinalizationReceipt activityFormal = new FormalFinalizationReceipt("intake-finalization-receipt.v1",
                    formal.operationKey(), formal.tenantSurrogate(), formal.caseId(), formal.roomEpoch(), formal.threadId(),
                    formal.actorScopeHash(), formal.agentSessionId(), formal.commandId(), formal.logicalRunId(), formal.attemptId(),
                    formal.resultHash(), formal.proposalHash(), formal.processRevision(), formal.roomRevision(), formal.fencingToken(),
                    formal.formalMessageId(), formal.dossierVersion(), formal.matrixVersion(), formal.domainEventIds(), formal.outboxIds(),
                    formal.status().name(), formal.committedAt().toString(), formal.receiptHash());
            return new TurnFinalizationReceipt("intake-turn-finalization-activity-receipt.v1",
                    new OperationReceipt("intake-operation-receipt.v1", formal.operationKey(), workflowCommandRequestHash,
                            formal.resultHash(), formal.processRevision(), formal.roomRevision()), activityFormal, committed);
        }
    }
}
