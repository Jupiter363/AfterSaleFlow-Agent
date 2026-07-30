package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptCodec;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            select receipt_id, activation_manifest_hash, receipt_canonical_bytes,
                   schema_version, execution_lane, activation_id, tenant_surrogate, case_id,
                   room_type, room_epoch, room_fencing_token, process_revision, stage_sequence,
                   logical_run_id, attempt_id, command_hash, command_envelope_hash,
                   graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                   result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                   agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                   receipt_hash, formal_writer, domain_commit_status
              from target_e2e_finalization_receipt
             where activation_id = :activationId
               and activation_manifest_hash = :activationManifestHash
               and execution_lane = 'TARGET_E2E_CANDIDATE'
               and tenant_surrogate = :tenantSurrogate
               and case_id = :caseId
               and room_type = 'INTAKE'
               and room_epoch = :roomEpoch
               and room_fencing_token = :roomFencingToken
               and process_revision = :processRevision
               and logical_run_id = :logicalRunId
               and attempt_id = :attemptId
               and command_hash = :commandHash
               and command_envelope_hash = :commandEnvelopeHash
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
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setReadOnly(true);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
                Map.entry("logicalRunId", child.logicalRunId()),
                Map.entry("attemptId", child.attemptId()),
                Map.entry("commandHash", target.commandHash()),
                Map.entry("commandEnvelopeHash", target.commandEnvelopeHash()));
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
        requireSingleCompletion(request, parameters, receipt);
        FormalProjection formal = readFormalProjection(request, receipt);
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
            IntakeAgentRunFinalizationReadRequest request,
            Map<String, Object> parameters,
            TargetE2eFinalizationReceipt receipt) {
        var command = request.command();
        Map<String, Object> completionParameters = new java.util.HashMap<>(parameters);
        completionParameters.put("commandId", command.commandId());
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
            IntakeAgentRunFinalizationReadRequest request, TargetE2eFinalizationReceipt targetReceipt) {
        var command = request.command();
        String operationKey = IntakeOperationKeys.turnFinalize(
                command.caseId(), command.roomEpoch(), command.executionContext().threadId(),
                command.commandId(), targetReceipt.resultHash());
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
                decodeFormalProjection(request, targetReceipt, operation, events.getFirst());
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
            OperationRow operation,
            EventRow event) {
        try {
            JsonNode document = objectMapper.readTree(event.eventJson());
            if (!"intake-turn-committed-event.v1".equals(document.path("schema_version").asText())
                    || !operation.requestHash().equals(document.path("request_hash").asText())
                    || !targetReceipt.resultHash().equals(document.path("result_hash").asText())
                    || !targetReceipt.proposalHash().equals(document.path("proposal_hash").asText())
                    || !event.id().equals(document.path("receipt").path("domain_event_ids").get(0).asText())) {
                throw rejected("TARGET_E2E_FINALIZATION_EVENT_MISMATCH", "formal Intake event conflicts with its receipt");
            }
            IntakeFinalizationReceipt formal = objectMapper.treeToValue(
                    document.required("receipt"), IntakeFinalizationReceipt.class);
            formal.requireCanonicalHash();
            IntakeDomainEventType eventType = IntakeDomainEventType.valueOf(event.eventType());
            if (eventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                    && eventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
                throw rejected("TARGET_E2E_FINALIZATION_EVENT_TYPE_INVALID", "formal Intake event is not a turn event");
            }
            requireFormalBinding(request, targetReceipt, formal, event);
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
        if (!receipt.activationId().equals(target.activationId())
                || !row.activationManifestHash().equals(target.activationManifestHash())
                || !receipt.tenantSurrogate().equals(command.tenantSurrogate())
                || !receipt.caseId().equals(command.caseId())
                || receipt.roomEpoch() != command.roomEpoch()
                || receipt.roomFencingToken() != command.fencingToken()
                || receipt.processRevision() != target.expectedProcessRevision()
                || !receipt.logicalRunId().equals(child.logicalRunId())
                || !receipt.attemptId().equals(child.attemptId())
                || !receipt.commandHash().equals(target.commandHash())
                || !receipt.commandEnvelopeHash().equals(target.commandEnvelopeHash())
                || !receipt.graphKey().equals(target.request().command().graphKey())
                || !receipt.graphVersion().equals(target.request().command().graphVersion())
                || !receipt.checkpointSchemaVersion().equals(target.request().command().checkpointSchemaVersion())
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
                || !formal.commandId().equals(command.commandId())
                || !formal.logicalRunId().equals(target.request().logicalRunId())
                || !formal.attemptId().equals(target.request().attemptId())
                || !formal.resultHash().equals(targetReceipt.resultHash())
                || !formal.proposalHash().equals(targetReceipt.proposalHash())
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

    private static FinalizationLocator locator(
            TargetE2eFinalizationReceipt receipt,
            String manifestHash,
            IntakeFinalizationReceipt formal) {
        return new FinalizationLocator(
                "intake-agent-run-finalization-locator.v1", receipt.executionLane(),
                receipt.activationId(), manifestHash, receipt.roomFencingToken(),
                receipt.logicalRunId(), receipt.attemptId(), receipt.resultHash(), receipt.proposalHash(),
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
                columns.agentRunManifestHash(), columns.isolatedDomainDbBindingHash());
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

    private record TargetRow(String receiptId, String activationManifestHash, byte[] canonicalBytes,
            TargetE2eFinalizationReceipt columns, String agentRunManifestId, String agentRunManifestHash,
            String isolatedDomainDbBindingHash) {}
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
