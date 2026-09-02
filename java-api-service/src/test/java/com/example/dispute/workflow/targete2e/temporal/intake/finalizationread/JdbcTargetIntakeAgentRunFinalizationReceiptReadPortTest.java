package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
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
import com.example.dispute.workflow.temporal.room.intake.IntakeParallelTurnContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final AgentPlatformContractCodec V1_CODEC = new AgentPlatformContractCodec();
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
    void readsCommittedV3AndV4ReceiptsUsingTheExactRequestProtocol() throws Exception {
        String targetReceiptSql = targetReceiptSql();
        assertThat(targetReceiptSql)
                .contains("run.protocol = :agentRunProtocol")
                .doesNotContain(
                        "run.protocol = 'agent-stream.v3'",
                        "run.protocol = 'agent-stream.v4'");

        for (AgentRunProtocol protocol : List.of(AgentRunProtocol.V3, AgentRunProtocol.V4)) {
            CommittedFixture fixture = committedFixture(protocol.wireValue());
            ReadHarness harness = readHarness(fixture);

            IntakeAgentRunFinalizationReadResult first = harness.port().read(fixture.request());
            IntakeAgentRunFinalizationReadResult replay = harness.port().read(fixture.request());

            assertThat(first.resolution())
                    .isEqualTo(IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED);
            assertThat(replay).isEqualTo(first);
            assertThatCode(() -> first.requireMatches(fixture.request())).doesNotThrowAnyException();
            assertThat(harness.targetParameters().get().get("agentRunProtocol"))
                    .isEqualTo(protocol.wireValue());
        }
    }

    @Test
    void rejectsMissingOrProfileInconsistentProtocolAuthority() throws Exception {
        RoomGraphCommand nonParallel =
                committedCommand(AgentRunProtocol.V3.wireValue())
                        .executionContext()
                        .targetAgentRun()
                        .request()
                        .command();
        RoomGraphCommand parallel =
                committedCommand(AgentRunProtocol.V4.wireValue())
                        .executionContext()
                        .targetAgentRun()
                        .request()
                        .command();

        for (ProtocolScenario scenario : List.of(
                new ProtocolScenario(null, nonParallel),
                new ProtocolScenario("", nonParallel),
                new ProtocolScenario(AgentRunProtocol.V3.wireValue(), parallel),
                new ProtocolScenario(AgentRunProtocol.V4.wireValue(), nonParallel))) {
            assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                            .requiredAgentRunProtocol(protocolAuthorityRequest(scenario)))
                    .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                    .extracting(
                            failure ->
                                    ((TargetE2eFinalizationRejectedException) failure).code())
                    .isEqualTo("TARGET_E2E_FINALIZATION_PROTOCOL_AUTHORITY_INVALID");
        }

        assertThatThrownBy(() -> new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        nonParallel.logicalRunId(),
                        1,
                        1,
                        AgentRunProtocol.V4.wireValue(),
                        hash('9'),
                        null,
                        false,
                        0,
                        nonParallel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("streamProtocol must match the explicit graph execution profile");
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
                "a".repeat(64), IntakeParty.INITIATOR,
                TargetTypedRoomProtocol.GRAPH_VERSION, "checkpoint-1");
        IntakeFinalizationReceipt formal = formalReceipt(6, 3);
        TurnFinalizationReceipt projected = projection(formal).toActivityReceipt(
                "a".repeat(64), IntakeParty.INITIATOR,
                TargetTypedRoomProtocol.GRAPH_VERSION, "checkpoint-1");

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
        IntakeWorkflowCommand command = committedCommand(AgentRunProtocol.V4.wireValue());
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

    private static CommittedFixture committedFixture(String protocol) throws Exception {
        IntakeWorkflowCommand command = committedCommand(protocol);
        IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
        ExecuteAgentRunRequest execution = target.request();
        IntakeAgentRunChildState child =
                IntakeAgentRunChildState.pending(
                                IntakeAgentRunChildIds.forCommand(command), target)
                        .resultReady(hash('a'));
        IntakeAgentRunFinalizationReadRequest request =
                IntakeAgentRunFinalizationReadRequest.winningAttempt(
                        IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
                        command,
                        child);
        Instant committedAt = Instant.parse("2026-08-30T00:00:00Z");
        TargetE2eFinalizationReceipt targetReceipt =
                TargetE2eFinalizationReceipt.committed(
                        new TargetE2eFinalizationReceipt.CommitFacts(
                                target.activationId(),
                                command.tenantSurrogate(),
                                command.caseId(),
                                RoomType.INTAKE,
                                command.roomEpoch(),
                                command.fencingToken(),
                                target.expectedProcessRevision(),
                                execution.command().stageSequence(),
                                execution.logicalRunId(),
                                execution.attemptId(),
                                target.commandHash(),
                                target.commandEnvelopeHash(),
                                execution.command().graphKey(),
                                execution.command().graphVersion(),
                                execution.command().checkpointSchemaVersion(),
                                "checkpoint-committed",
                                child.resultHash(),
                                hash('b'),
                                hash('c'),
                                "agent-run-manifest-committed",
                                hash('d'),
                                hash('e'),
                                committedAt));

        String eventId = "event-committed";
        String operationRequestHash = hash('f');
        String operationKey =
                IntakeOperationKeys.turnFinalize(
                        command.caseId(),
                        command.roomEpoch(),
                        command.executionContext().threadId(),
                        execution.command().commandId(),
                        targetReceipt.resultHash());
        IntakeFinalizationReceipt formal =
                IntakeFinalizationReceipt.committed(
                        new IntakeFinalizationReceipt.CommitFacts(
                                operationKey,
                                command.tenantSurrogate(),
                                command.caseId(),
                                command.roomEpoch(),
                                command.executionContext().threadId(),
                                command.actorScopeHash(),
                                command.executionContext().agentSessionId(),
                                execution.command().commandId(),
                                execution.logicalRunId(),
                                execution.attemptId(),
                                targetReceipt.resultHash(),
                                hash('1'),
                                target.expectedProcessRevision(),
                                target.expectedRoomRevision(),
                                command.fencingToken(),
                                "message-committed",
                                1L,
                                1L,
                                List.of(eventId),
                                List.of("outbox-committed"),
                                committedAt));
        ObjectNode eventDocument = MAPPER.createObjectNode();
        eventDocument.put("schema_version", "intake-turn-committed-event.v1");
        eventDocument.put("request_hash", operationRequestHash);
        eventDocument.put("result_hash", targetReceipt.resultHash());
        eventDocument.put("proposal_hash", formal.proposalHash());
        eventDocument.set("receipt", MAPPER.valueToTree(formal));

        ObjectMapper materialMapper =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.targetMaterialObjectMapper(
                        MAPPER);
        String materialJson =
                ContractJson.canonicalString(
                        materialMapper.valueToTree(command.executionContext()));
        Object targetRow =
                privateRecord(
                        "TargetRow",
                        "target-receipt-committed",
                        target.activationManifestHash(),
                        TargetE2eFinalizationReceiptCodec.canonicalBytes(targetReceipt),
                        targetReceipt,
                        targetReceipt.agentRunManifestId(),
                        targetReceipt.agentRunManifestHash(),
                        targetReceipt.isolatedDomainDbBindingHash(),
                        execution.command().commandId(),
                        execution.command().requestHash(),
                        ContractJson.canonicalString(V1_CODEC.encode(
                                "room-graph-command.schema.json", execution.command())),
                        execution.attemptNo(),
                        execution.attemptLimit(),
                        execution.logicalInputHash(),
                        execution.previousAttemptId(),
                        execution.resetRequired(),
                        execution.publicSequenceOffset(),
                        materialJson,
                        ContractJson.sha256Hex(
                                materialMapper.valueToTree(command.executionContext())));
        Object operationRow =
                privateRecord(
                        "OperationRow",
                        operationRequestHash,
                        "urn:intake:finalization-receipt:" + eventId,
                        formal.receiptHash(),
                        "COMPLETED",
                        command.caseId(),
                        command.roomEpoch(),
                        targetReceipt.processRevision(),
                        command.fencingToken());
        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.EventRow eventRow =
                new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.EventRow(
                        eventId,
                        command.caseId(),
                        1,
                        "TURN_NEEDS_INPUT",
                        MAPPER.writeValueAsString(eventDocument));
        return new CommittedFixture(
                request,
                targetRow,
                operationRow,
                eventRow,
                targetReceipt.receiptHash());
    }

    private static IntakeWorkflowCommand committedCommand(String protocol) throws Exception {
        IntakeWorkflowCommand template = terminalCommand();
        IntakeTargetAgentRunContext templateTarget =
                template.executionContext().targetAgentRun();
        RoomGraphCommand templateGraph = templateTarget.request().command();
        boolean parallel = AgentRunProtocol.V4.wireValue().equals(protocol);
        RoomGraphCommand graph =
                parallel ? parallelCommand(templateGraph) : canonicalCommand(templateGraph);
        ExecuteAgentRunRequest execution =
                new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        graph.logicalRunId(),
                        1,
                        1,
                        protocol,
                        hash('6'),
                        null,
                        false,
                        0,
                        graph);
        String activationId = "p9act.v1." + "1".repeat(32);
        long fencingToken = template.fencingToken();
        var sealed =
                new TargetE2EGraphEnvelopeCodec(MAPPER)
                        .wrapCommand(activationId, fencingToken, graph);
        IntakeParallelTurnContext parallelTurn = null;
        if (parallel) {
            ObjectNode previousDossier = MAPPER.createObjectNode();
            previousDossier.put("schema_version", "intake-dossier.v2");
            parallelTurn =
                    new IntakeParallelTurnContext(
                            IntakeParallelTurnContext.SCHEMA_VERSION,
                            IntakeParallelTurnContext.SOURCE_TYPE,
                            "message-source-committed",
                            "补充当前事实。",
                            IntakeParallelTurnContext.messageHash("补充当前事实。"),
                            1,
                            previousDossier,
                            ContractJson.sha256Hex(previousDossier),
                            graph.domainSnapshotRef().sha256(),
                            graph.eventRef().sha256(),
                            "provider-test",
                            graph.invocationContext().modelProfileId());
        }
        IntakeTargetAgentRunContext target =
                new IntakeTargetAgentRunContext(
                        IntakeTargetAgentRunContext.INITIAL_SCHEMA_VERSION,
                        IntakeTargetAgentRunContext.TARGET_LANE,
                        activationId,
                        hash('2'),
                        fencingToken,
                        templateTarget.expectedProcessRevision(),
                        templateTarget.expectedRoomRevision(),
                        templateTarget.caseBuildId(),
                        templateTarget.controlBuildId(),
                        templateTarget.agentBuildId(),
                        templateTarget.graphBindingHash(),
                        templateTarget.graphCodeBuildId(),
                        sealed.commandHash(),
                        sealed.commandEnvelopeHash(),
                        execution,
                        parallelTurn);
        IntakeCommandExecutionContext context =
                new IntakeCommandExecutionContext(
                        "intake-command-execution-context.v2",
                        graph.threadId(),
                        template.executionContext().agentSessionId(),
                        template.executionContext().deadlineEpochMillis(),
                        template.executionContext().retryBudget(),
                        null,
                        target);
        return new IntakeWorkflowCommand(
                template.schemaVersion(),
                graph.commandId(),
                graph.tenantSurrogate(),
                graph.caseId(),
                graph.roomEpoch(),
                fencingToken,
                templateTarget.expectedRoomRevision(),
                template.commandType(),
                template.party(),
                template.actorScopeHash(),
                template.payloadRef(),
                template.payloadHash(),
                template.operationKey(),
                template.requestHash(),
                context);
    }

    private static RoomGraphCommand parallelCommand(RoomGraphCommand source) throws Exception {
        RoomGraphCommand.SnapshotRef eventRef =
                new RoomGraphCommand.SnapshotRef(
                        "event-source-committed",
                        "room-message.v1",
                        "urn:room-message:committed",
                        hash('3'),
                        128);
        RoomGraphCommand.InvocationContext invocation =
                new RoomGraphCommand.InvocationContext(
                        RoomGraphCommand.PARALLEL_INTAKE_AGENT_PROFILE_ID,
                        source.invocationContext().promptProfileId(),
                        source.invocationContext().modelProfileId(),
                        RoomGraphCommand.PARALLEL_INTAKE_OUTPUT_SCHEMA,
                        source.invocationContext().policyVersion(),
                        source.invocationContext().guardrailVersion(),
                        source.invocationContext().toolCapabilities(),
                        source.invocationContext().envelopeKeyId(),
                        source.invocationContext().envelopeNonce());
        RoomGraphCommand provisional =
                new RoomGraphCommand(
                        source.schemaVersion(),
                        source.commandId(),
                        source.logicalRunId(),
                        source.attemptId(),
                        source.tenantSurrogate(),
                        source.caseId(),
                        "ROOM_COMMITTED",
                        source.roomType(),
                        source.roomEpoch(),
                        source.graphKey(),
                        source.graphVersion(),
                        source.checkpointSchemaVersion(),
                        source.threadId(),
                        source.actorScope(),
                        source.processRevision(),
                        source.stageCode(),
                        source.stageSequence(),
                        source.domainSnapshotRef(),
                        eventRef,
                        invocation,
                        new RoomGraphCommand.RetryBudget(3, 3, 1),
                        source.deadlineAt(),
                        source.traceparent(),
                        hash('0'));
        return canonicalCommand(provisional);
    }

    private static RoomGraphCommand canonicalCommand(RoomGraphCommand provisional) {
        ObjectNode canonical =
                (ObjectNode) V1_CODEC.encode("room-graph-command.schema.json", provisional);
        canonical.remove("request_hash");
        canonical.put("request_hash", ContractJson.sha256Hex(canonical));
        return V1_CODEC.decode(
                "room-graph-command.schema.json", canonical, RoomGraphCommand.class);
    }

    private static ReadHarness readHarness(CommittedFixture fixture) {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        AtomicReference<Map<String, Object>> targetParameters = new AtomicReference<>();
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            Map<String, Object> parameters = invocation.getArgument(1);
                            if (sql.contains("from target_e2e_finalization_receipt receipt")) {
                                targetParameters.set(Map.copyOf(parameters));
                                return List.of(fixture.targetRow());
                            }
                            if (sql.contains("from domain_operation")) {
                                return List.of(fixture.operationRow());
                            }
                            if (sql.contains("from agent_run run")) {
                                return List.of();
                            }
                            if (sql.contains("from case_timeline_event")) {
                                return List.of(fixture.eventRow());
                            }
                            throw new AssertionError("unexpected receipt read query");
                        });
        when(jdbc.queryForList(anyString(), any(Map.class), eq(String.class)))
                .thenReturn(List.of(fixture.targetReceiptHash()));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return new ReadHarness(
                new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
                        jdbc, transactions, MAPPER),
                targetParameters);
    }

    private static IntakeAgentRunFinalizationReadRequest protocolAuthorityRequest(
            ProtocolScenario scenario) {
        IntakeAgentRunFinalizationReadRequest request =
                mock(IntakeAgentRunFinalizationReadRequest.class);
        IntakeWorkflowCommand command = mock(IntakeWorkflowCommand.class);
        IntakeCommandExecutionContext context = mock(IntakeCommandExecutionContext.class);
        IntakeTargetAgentRunContext target = mock(IntakeTargetAgentRunContext.class);
        ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
        when(request.command()).thenReturn(command);
        when(command.executionContext()).thenReturn(context);
        when(context.targetAgentRun()).thenReturn(target);
        when(target.request()).thenReturn(execution);
        when(execution.streamProtocol()).thenReturn(scenario.protocol());
        when(execution.command()).thenReturn(scenario.command());
        return request;
    }

    private static Object privateRecord(String simpleName, Object... values) throws Exception {
        Class<?> type =
                Class.forName(
                        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class.getName()
                                + "$"
                                + simpleName);
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static String targetReceiptSql() throws Exception {
        var field =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class.getDeclaredField(
                        "TARGET_RECEIPT_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record CommittedFixture(
            IntakeAgentRunFinalizationReadRequest request,
            Object targetRow,
            Object operationRow,
            JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.EventRow eventRow,
            String targetReceiptHash) {}

    private record ReadHarness(
            JdbcTargetIntakeAgentRunFinalizationReceiptReadPort port,
            AtomicReference<Map<String, Object>> targetParameters) {}

    private record ProtocolScenario(String protocol, RoomGraphCommand command) {}

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
                AgentRunProtocol.V3.wireValue(),
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
                request.streamProtocol(),
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
                V1_CODEC.encode("room-graph-command.schema.json", request.command()).toString(),
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
                        "a".repeat(64), IntakeParty.INITIATOR,
                        TargetTypedRoomProtocol.GRAPH_VERSION, "checkpoint-1"))
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
