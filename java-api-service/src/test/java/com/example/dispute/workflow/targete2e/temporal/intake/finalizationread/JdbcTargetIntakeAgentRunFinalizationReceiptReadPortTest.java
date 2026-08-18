package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunChildIds;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunChildState;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path COMMAND_FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");
    private static final Path FORMAL_RECEIPT_FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "intake",
            "v2",
            "fixtures",
            "valid",
            "intake-finalization-receipt-valid.json");
    private static final Path RESULT_FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-result-valid.json");

    private static final String TARGET_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "target-e2e-finalization-receipt.v1",
                    "receipt_id", "target-receipt-1")));
    private static final String FORMAL_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "intake-finalization-receipt.v1",
                    "operation_key", "intake-operation-1")));
    private static final String OUTER_PROPOSAL_DESCRIPTOR_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "target-e2e-proposal-descriptor.v1",
                    "proposal_uri", "minio://target/proposal.json")));
    private static final String FORMAL_PROPOSAL_PAYLOAD_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "intake-turn-proposal.v2",
                    "room_utterance", "Additional details are required.")));

    @Test
    void isAnExplicitFrameworkFreeReadPort() {
        assertThat(IntakeAgentRunFinalizationReceiptReadPort.class)
                .isAssignableFrom(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class);
    }

    @Test
    void rejectsMissingPersistenceDependenciesAtAssembly() {
        assertThatThrownBy(() -> new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
                (NamedParameterJdbcOperations) null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jdbc");
    }

    @Test
    void acceptsDistinctCanonicalTargetCompletionAndFormalOperationHashes() {
        assertThat(TARGET_RECEIPT_HASH).isNotEqualTo(FORMAL_RECEIPT_HASH);
        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalCompletionHash(TARGET_RECEIPT_HASH, TARGET_RECEIPT_HASH))
                .doesNotThrowAnyException();
        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash(FORMAL_RECEIPT_HASH, FORMAL_RECEIPT_HASH))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsDistinctOuterDescriptorAndFormalPayloadProposalHashes() {
        assertThat(OUTER_PROPOSAL_DESCRIPTOR_HASH).isNotEqualTo(FORMAL_PROPOSAL_PAYLOAD_HASH);

        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireFormalEventProposalHash(
                        FORMAL_PROPOSAL_PAYLOAD_HASH, FORMAL_PROPOSAL_PAYLOAD_HASH))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTamperedFormalEventProposalHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireFormalEventProposalHash(FORMAL_PROPOSAL_PAYLOAD_HASH, "ab".repeat(32)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake event conflicts with its receipt")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_EVENT_MISMATCH");
    }

    @Test
    void rejectsLegacyIdentityOnlyCompletionHash() {
        String identityOnlyHash = ContractJson.sha256Hex(JsonMapper.builder().build().valueToTree(Map.of(
                "activation_id", "activation-1",
                "command_id", "command-1",
                "command_hash", "11".repeat(32),
                "command_envelope_hash", "22".repeat(32))));

        assertThat(identityOnlyHash).isNotEqualTo(TARGET_RECEIPT_HASH);
        assertCompletionHashRejected(identityOnlyHash);
    }

    @Test
    void rejectsTamperedReceiptCompletionHash() {
        assertCompletionHashRejected("cd".repeat(32));
    }

    @Test
    void rejectsTargetReceiptHashUsedAsFormalOperationHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash(TARGET_RECEIPT_HASH, FORMAL_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake operation hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_OPERATION_HASH_MISMATCH");
    }

    @Test
    void rejectsTamperedFormalOperationHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash("ef".repeat(32), FORMAL_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake operation hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_OPERATION_HASH_MISMATCH");
    }

    @Test
    void acceptsJsonbStyleWinningAttemptTextWhenItsCanonicalHashMatches() throws Exception {
        ObjectNode commandJson = commandJson();
        String jsonbStyleText =
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(commandJson);

        RoomGraphCommand command =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                        MAPPER,
                        jsonbStyleText,
                        commandJson.required("request_hash").textValue());

        assertThat(command).isEqualTo(MAPPER.treeToValue(commandJson, RoomGraphCommand.class));
    }

    @Test
    void rejectsJsonbStyleWinningAttemptTextWhenItsBodyWasTampered() throws Exception {
        ObjectNode commandJson = commandJson();
        String requestHash = commandJson.required("request_hash").textValue();
        commandJson.put("stage_sequence", commandJson.required("stage_sequence").longValue() + 1);

        assertThatThrownBy(() ->
                        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                                MAPPER,
                                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(commandJson),
                                requestHash))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("winning attempt command self-hash is invalid")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID");
    }

    @Test
    void rejectsWinningAttemptWhenItsPersistedRequestHashWasTampered() throws Exception {
        ObjectNode commandJson = commandJson();

        assertThatThrownBy(() ->
                        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                                MAPPER,
                                MAPPER.writeValueAsString(commandJson),
                                "f".repeat(64)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("winning attempt command self-hash is invalid")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID");
    }

    @Test
    void decodesCamelCaseIntakeMaterialWithAnInjectedSnakeCaseMapper() throws Exception {
        IntakeCommandExecutionContext expected = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v1",
                "grt.v1." + "1".repeat(32),
                "agent-session-1",
                1_800_000_000_000L,
                new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
                null,
                null);
        ObjectMapper camelCaseMapper = MAPPER.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        String canonicalMaterial =
                ContractJson.canonicalString(camelCaseMapper.valueToTree(expected));
        ObjectMapper injectedSnakeCaseMapper = MAPPER.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        IntakeCommandExecutionContext decoded =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                        .targetMaterialObjectMapper(injectedSnakeCaseMapper)
                        .readValue(canonicalMaterial, IntakeCommandExecutionContext.class);

        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void hashesTheFormalEventDocumentInsteadOfItsJsonStringEncoding() throws Exception {
        JsonNode event = MAPPER.readTree("""
                {
                  "schema_version": "intake-turn-committed-event.v1",
                  "event_type": "TURN_NEEDS_INPUT",
                  "receipt": {"domain_event_ids": ["event-1"]}
                }
                """);

        assertThat(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.canonicalEventHash(event))
                .isEqualTo(ContractJson.sha256Hex(event));
    }

    @Test
    void projectsFormalAuthorityPreconditionsToOneCommittedSuccessorRevision() throws Exception {
        IntakeFinalizationReceipt freshFormal = formalReceipt(0, 0);
        TurnFinalizationReceipt freshProjected = projection(freshFormal).toActivityReceipt(
                "a".repeat(64), IntakeParty.INITIATOR, "checkpoint-1");
        IntakeFinalizationReceipt formal = formalReceipt(6, 3);
        TurnFinalizationReceipt projected = projection(formal).toActivityReceipt(
                "a".repeat(64), IntakeParty.INITIATOR, "checkpoint-1");

        assertThat(freshProjected.operation().processRevision()).isEqualTo(1);
        assertThat(freshProjected.operation().roomRevision()).isEqualTo(1);
        assertThat(freshProjected.formalReceipt().processRevision()).isEqualTo(1);
        assertThat(freshProjected.formalReceipt().roomRevision()).isEqualTo(1);
        assertThat(freshProjected.committedEvent().processRevision()).isEqualTo(1);
        assertThat(freshProjected.committedEvent().roomRevision()).isEqualTo(1);
        assertThat(formal.processRevision()).isEqualTo(6);
        assertThat(formal.roomRevision()).isEqualTo(3);
        assertThat(projected.operation().processRevision()).isEqualTo(7);
        assertThat(projected.operation().roomRevision()).isEqualTo(4);
        assertThat(projected.formalReceipt().processRevision()).isEqualTo(7);
        assertThat(projected.formalReceipt().roomRevision()).isEqualTo(4);
        assertThat(projected.committedEvent().processRevision()).isEqualTo(7);
        assertThat(projected.committedEvent().roomRevision()).isEqualTo(4);
        assertThat(projected.formalReceipt().receiptHash()).isEqualTo(formal.receiptHash());
    }

    @Test
    void rejectsCommittedSuccessorRevisionOverflowWithoutWrapping() throws Exception {
        assertProjectionOverflow(Long.MAX_VALUE, 3);
        assertProjectionOverflow(6, Long.MAX_VALUE);
    }

    @Test
    void pendingChildWithDurableFinalizationRejectionResolvesExactTerminalNoCommitAuthority()
            throws Exception {
        IntakeWorkflowCommand command = terminalCommand();
        IntakeAgentRunChildState child = IntakeAgentRunChildState.pending(
                IntakeAgentRunChildIds.forCommand(command),
                command.executionContext().targetAgentRun());
        IntakeAgentRunFinalizationReadRequest request =
                IntakeAgentRunFinalizationReadRequest.winningAttempt(
                        IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
                        command,
                        child);
        ExecuteAgentRunResult completed = completedAudit(command);
        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.TerminalRow exact =
                terminalRow(command, completed, 0, 0, 0);
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class)))
                .thenReturn(List.of());
        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort port =
                new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
                        jdbc, mock(PlatformTransactionManager.class), MAPPER);

        IntakeAgentRunFinalizationReadResult resolved =
                port.terminalNoCommit(request, List.of(exact));

        assertThat(resolved.schemaVersion())
                .isEqualTo(IntakeAgentRunFinalizationReadResult.SCHEMA_VERSION);
        assertThat(resolved.resolution())
                .isEqualTo(IntakeAgentRunFinalizationReadResult.Resolution.TERMINAL_NO_COMMIT);
        assertThat(resolved.terminalNoCommitEvidence().completedAudit()).isEqualTo(completed);
        assertThat(resolved.terminalNoCommitEvidence().terminalResult().outcome())
                .isEqualTo(ExecuteAgentRunResult.Outcome.FAILED);
        assertThat(resolved.terminalNoCommitEvidence().terminalResult().errorCode())
                .isEqualTo("INTAKE_RESPONDENT_MATRIX_NOT_READY");
        assertThatCode(() -> resolved.requireMatches(request)).doesNotThrowAnyException();

        IntakeAgentRunFinalizationReadResult legacyPending =
                new IntakeAgentRunFinalizationReadResult(
                        IntakeAgentRunFinalizationReadResult.LEGACY_SCHEMA_VERSION,
                        IntakeAgentRunFinalizationReadResult.Resolution.PENDING,
                        null,
                        null);
        JsonNode legacyJson = MAPPER.valueToTree(legacyPending);
        assertThat(legacyJson.has("terminalNoCommitEvidence")).isFalse();
        assertThat(MAPPER.treeToValue(legacyJson, IntakeAgentRunFinalizationReadResult.class))
                .isEqualTo(legacyPending);

        assertThatThrownBy(
                        () -> port.terminalNoCommit(
                                request, List.of(terminalRow(command, completed, 0, 1, 0))))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .extracting(
                        failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_TERMINAL_AUTHORITY_INVALID");
        assertThatThrownBy(
                        () -> port.terminalNoCommit(
                                request, List.of(terminalRow(command, completed, 1, 0, 0))))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .extracting(
                        failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_TERMINAL_AUTHORITY_INVALID");
        assertThatThrownBy(
                        () -> port.terminalNoCommit(
                                request, List.of(terminalRow(command, completed, 0, 0, 1))))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .extracting(
                        failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_TERMINAL_EVENT_PRESENT");
    }

    private static IntakeWorkflowCommand terminalCommand() throws Exception {
        ObjectNode commandJson = commandJson();
        commandJson.put("command_id", "CMD_FINALIZATION_REJECTED");
        commandJson.put("logical_run_id", "RUN_FINALIZATION_REJECTED");
        commandJson.put("attempt_id", "ATTEMPT_FINALIZATION_REJECTED_1");
        commandJson.put("tenant_surrogate", "tenant-finalization-rejected");
        commandJson.put("case_id", "CASE_FINALIZATION_REJECTED");
        commandJson.put("room_type", "INTAKE");
        commandJson.put("room_epoch", 9);
        commandJson.put("process_revision", 6);
        commandJson.put("request_hash", "0".repeat(64));
        RoomGraphCommand provisional =
                MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        ObjectNode canonicalCommand = MAPPER.valueToTree(provisional);
        canonicalCommand.remove("request_hash");
        canonicalCommand.put("request_hash", ContractJson.sha256Hex(canonicalCommand));
        RoomGraphCommand graph =
                MAPPER.treeToValue(canonicalCommand, RoomGraphCommand.class);
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                graph.logicalRunId(),
                1,
                1,
                "agent-stream.v3",
                "6".repeat(64),
                null,
                false,
                0,
                graph);
        IntakeTargetAgentRunContext target = new IntakeTargetAgentRunContext(
                "intake-target-agent-run-context.v1",
                IntakeTargetAgentRunContext.TARGET_LANE,
                "p9act.v1." + "1".repeat(32),
                "2".repeat(64),
                11,
                6,
                6,
                "case-build-finalization",
                "control-build-finalization",
                "agent-build-finalization",
                "3".repeat(64),
                "graph-build-finalization",
                "4".repeat(64),
                "5".repeat(64),
                request);
        IntakeCommandExecutionContext context = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v2",
                graph.threadId(),
                "agent-session-finalization",
                Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 1, 1, 0),
                null,
                target);
        return new IntakeWorkflowCommand(
                "intake-workflow-command.v1",
                graph.commandId(),
                graph.tenantSurrogate(),
                graph.caseId(),
                graph.roomEpoch(),
                11,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.INITIATOR,
                "7".repeat(64),
                "urn:room-message:CMD_FINALIZATION_REJECTED",
                "9".repeat(64),
                "intake.operation:" + graph.caseId() + ":" + graph.commandId(),
                "8".repeat(64),
                context);
    }

    private static ExecuteAgentRunResult completedAudit(IntakeWorkflowCommand command)
            throws Exception {
        ExecuteAgentRunRequest request = command.executionContext().targetAgentRun().request();
        JsonNode wrapper = MAPPER.readTree(RESULT_FIXTURE.toFile());
        RoomGraphResult fixture = MAPPER.treeToValue(
                wrapper.required("instance"), RoomGraphResult.class);
        RoomGraphResult graph = new RoomGraphResult(
                fixture.schemaVersion(),
                request.command().commandId(),
                request.logicalRunId(),
                request.attemptId(),
                request.command().graphKey(),
                request.command().graphVersion(),
                fixture.checkpointId(),
                fixture.cognitiveRevision(),
                fixture.status(),
                fixture.publicEventProposals(),
                fixture.artifactOperations(),
                fixture.needsInput(),
                fixture.needsReview(),
                fixture.error(),
                fixture.outputHash(),
                fixture.usage(),
                fixture.executionMetadata());
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graph,
                graph.outputHash(),
                2,
                true,
                null,
                false,
                null,
                Instant.parse("2026-08-10T16:07:06.400Z"));
    }

    private static JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.TerminalRow terminalRow(
            IntakeWorkflowCommand command,
            ExecuteAgentRunResult completed,
            long completionCount,
            long receiptCount,
            long formalEventCount) throws Exception {
        IntakeCommandExecutionContext context = command.executionContext();
        IntakeTargetAgentRunContext target = context.targetAgentRun();
        ExecuteAgentRunRequest request = target.request();
        ObjectMapper materialMapper =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.targetMaterialObjectMapper(
                        MAPPER);
        String materialJson = ContractJson.canonicalString(materialMapper.valueToTree(context));
        OffsetDateTime completedAt =
                OffsetDateTime.ofInstant(completed.completedAt(), ZoneOffset.UTC);
        return new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.TerminalRow(
                request.logicalRunId(),
                command.tenantSurrogate(),
                command.caseId(),
                "agent-stream.v3",
                "TEMPORAL_ACTIVITY",
                "INTAKE",
                command.roomEpoch(),
                target.expectedProcessRevision(),
                command.fencingToken(),
                request.command().requestHash(),
                request.logicalInputHash(),
                request.attemptLimit(),
                "ABORTED",
                "FINALIZATION_REJECTED",
                "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                false,
                "UNCOMMITTED",
                request.attemptId(),
                null,
                completed.resultHash(),
                null,
                null,
                null,
                null,
                completedAt,
                request.attemptId(),
                request.attemptNo(),
                AgentRunAttemptStatus.ABORTED.name(),
                "TEMPORAL_ACTIVITY",
                request.command().requestHash(),
                "agent-run-attempt-lineage.v1",
                request.command().commandId(),
                request.command().requestHash(),
                request.logicalInputHash(),
                MAPPER.writeValueAsString(request.command()),
                null,
                false,
                0,
                "FAIL_LOGICAL_RUN",
                completed.resultHash(),
                MAPPER.writeValueAsString(completed),
                "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                false,
                true,
                true,
                completed.lastSequenceNo() + 1,
                completedAt,
                "p9cmd.v1." + "9".repeat(32),
                target.commandHash(),
                target.commandEnvelopeHash(),
                "p9cmd.v1." + "9".repeat(32),
                materialJson,
                ContractJson.sha256Hex(materialMapper.valueToTree(context)),
                completionCount,
                receiptCount,
                formalEventCount);
    }

    private static ObjectNode commandJson() throws Exception {
        JsonNode wrapper = MAPPER.readTree(COMMAND_FIXTURE.toFile());
        return (ObjectNode) wrapper.required("instance").deepCopy();
    }

    private static JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.FormalProjection projection(
            IntakeFinalizationReceipt formal) {
        return new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.FormalProjection(
                formal,
                new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.EventRow(
                        formal.domainEventIds().getFirst(),
                        formal.caseId(),
                        1,
                        "TURN_NEEDS_INPUT",
                        "{}"),
                "b".repeat(64),
                "c".repeat(64));
    }

    private static IntakeFinalizationReceipt formalReceipt(
            long processRevision, long roomRevision) throws Exception {
        IntakeFinalizationReceipt fixture =
                MAPPER.readValue(FORMAL_RECEIPT_FIXTURE.toFile(), IntakeFinalizationReceipt.class);
        return IntakeFinalizationReceipt.committed(new IntakeFinalizationReceipt.CommitFacts(
                fixture.operationKey(),
                fixture.tenantSurrogate(),
                fixture.caseId(),
                fixture.roomEpoch(),
                fixture.threadId(),
                fixture.actorScopeHash(),
                fixture.agentSessionId(),
                fixture.commandId(),
                fixture.logicalRunId(),
                fixture.attemptId(),
                fixture.resultHash(),
                fixture.proposalHash(),
                processRevision,
                roomRevision,
                fixture.fencingToken(),
                fixture.formalMessageId(),
                fixture.dossierVersion(),
                fixture.matrixVersion(),
                fixture.domainEventIds(),
                fixture.outboxIds(),
                fixture.committedAt()));
    }

    private static void assertProjectionOverflow(
            long processRevision, long roomRevision) throws Exception {
        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.FormalProjection projection =
                projection(formalReceipt(processRevision, roomRevision));

        assertThatThrownBy(() -> projection.toActivityReceipt(
                        "a".repeat(64), IntakeParty.INITIATOR, "checkpoint-1"))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake revision cannot advance to its committed successor")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_REVISION_OVERFLOW");
    }

    private static void assertCompletionHashRejected(String completionHash) {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalCompletionHash(completionHash, TARGET_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("target command completion hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_COMPLETION_HASH_MISMATCH");
    }
}
