package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore;
import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitReceipt;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitterRegistry;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunFormalResultCommitterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:05:00Z");
    private static final long FENCING_TOKEN = 7;

    @Mock private AgentRunLedger ledger;
    @Mock private AgentExecutionManifestStore manifestStore;
    @Mock private AgentRunDomainResultCommitter domainCommitter;

    private AgentRunFormalResultCommitter committer;

    @BeforeEach
    void setUp() {
        committer = new AgentRunFormalResultCommitter(
                ledger,
                new AgentRunDomainResultCommitterRegistry(List.of(domainCommitter)),
                manifestStore,
                MAPPER);
    }

    @Test
    void writesDomainFactBeforeManifestInsideTheFormalCommitBoundary() throws Exception {
        FormalResultCommit command = formalCommit();
        when(ledger.committedReceipt(command.request().agentRunId()))
                .thenReturn(Optional.empty());
        when(domainCommitter.supports(
                        command.request().command().roomType(),
                        command.request().command().graphKey()))
                .thenReturn(true);
        when(domainCommitter.commit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(domainReceipt(command));
        AgentRunFinalizationReceipt receipt = receipt(command, CommitStatus.COMMITTED);
        when(manifestStore.append(command.manifestCommit())).thenReturn(receipt);

        assertThat(committer.commit(command)).isEqualTo(receipt);

        InOrder order = inOrder(domainCommitter, manifestStore);
        order.verify(domainCommitter).commit(org.mockito.ArgumentMatchers.any());
        order.verify(manifestStore).append(command.manifestCommit());
    }

    @Test
    void committedReplayReturnsSameReceiptWithoutCallingDomainWriterAgain() throws Exception {
        FormalResultCommit command = formalCommit();
        AgentRunFinalizationReceipt committed = receipt(command, CommitStatus.COMMITTED);
        when(ledger.committedReceipt(command.request().agentRunId()))
                .thenReturn(Optional.of(committed));

        AgentRunFinalizationReceipt replay = committer.commit(command);

        assertThat(replay.commitStatus()).isEqualTo(CommitStatus.ALREADY_COMMITTED);
        assertThat(replay.manifestId()).isEqualTo(committed.manifestId());
        assertThat(replay.finalResultHash()).isEqualTo(committed.finalResultHash());
        assertThat(replay.committedAt()).isEqualTo(committed.committedAt());
        verify(domainCommitter, never()).commit(org.mockito.ArgumentMatchers.any());
        verify(manifestStore, never()).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleStageReceiptFailsClosedBeforeManifestAppend() throws Exception {
        FormalResultCommit command = formalCommit();
        when(ledger.committedReceipt(command.request().agentRunId()))
                .thenReturn(Optional.empty());
        when(domainCommitter.supports(
                        command.request().command().roomType(),
                        command.request().command().graphKey()))
                .thenReturn(true);
        CommitReceipt valid = domainReceipt(command);
        when(domainCommitter.commit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CommitReceipt(
                        valid.formalObjectId(),
                        valid.caseId(),
                        valid.roomEpoch(),
                        valid.processRevision(),
                        valid.stageCode(),
                        valid.stageSequence() + 1,
                        valid.actorId(),
                        valid.actorRole(),
                        valid.audience(),
                        valid.fencingToken(),
                        valid.resultHash()));

        assertThatThrownBy(() -> committer.commit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorized fence");
        verify(manifestStore, never()).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingRoomCommitterFailsClosed() throws Exception {
        FormalResultCommit command = formalCommit();
        when(ledger.committedReceipt(command.request().agentRunId()))
                .thenReturn(Optional.empty());
        committer = new AgentRunFormalResultCommitter(
                ledger,
                new AgentRunDomainResultCommitterRegistry(List.of()),
                manifestStore,
                MAPPER);

        assertThatThrownBy(() -> committer.commit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one formal result committer");
        verify(manifestStore, never()).append(org.mockito.ArgumentMatchers.any());
    }

    private static FormalResultCommit formalCommit() throws Exception {
        RoomGraphCommand graphCommand = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        RoomGraphResult graphResult = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                graphCommand.logicalRunId(),
                1,
                "agent-stream.v2",
                graphCommand);
        ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                2,
                true,
                null,
                false,
                NOW);
        AgentExecutionManifest manifest = manifest(request, result);
        String manifestHash = ContractJson.sha256Hex(MAPPER.valueToTree(manifest));
        ManifestCommit manifestCommit = new ManifestCommit(
                manifest,
                graphCommand.roomType(),
                "s3://manifest/" + manifest.manifestId() + ".json",
                manifestHash,
                result.resultHash(),
                result.lastSequenceNo());
        return new FormalResultCommit(request, result, manifestCommit);
    }

    private static AgentExecutionManifest manifest(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        RoomGraphCommand command = request.command();
        RoomGraphResult graph = result.graphResult();
        RoomGraphResult.ExecutionMetadata metadata = graph.executionMetadata();
        return new AgentExecutionManifest(
                "agent-execution-manifest.v1",
                "manifest-001",
                command.tenantSurrogate(),
                command.caseId(),
                command.roomEpoch(),
                command.processRevision(),
                FENCING_TOKEN,
                new AgentExecutionManifest.WorkflowRef(
                        TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId()),
                        "temporal-run-001",
                        "AgentRunV2Workflow",
                        "test-build"),
                new AgentExecutionManifest.AgentRunRef(
                        request.logicalRunId(), request.attemptId(), "logical-key-001"),
                new AgentExecutionManifest.GraphRef(
                        graph.graphKey(),
                        graph.graphVersion(),
                        command.checkpointSchemaVersion(),
                        graph.checkpointId(),
                        graph.cognitiveRevision()),
                new AgentExecutionManifest.ModelRef(
                        metadata.promptVersion(),
                        metadata.modelProfileId(),
                        "test-provider",
                        "test-model",
                        command.requestHash(),
                        result.resultHash()),
                Map.of(
                        "room_graph_command", command.schemaVersion(),
                        "room_graph_result", graph.schemaVersion()),
                metadata.policyVersion(),
                metadata.guardrailVersion(),
                List.of(),
                List.of(),
                new ArtifactPointer(
                        "formal-result-001",
                        metadata.schemaVersion(),
                        "s3://graph-output/formal-result-001.json",
                        result.resultHash()),
                new AgentExecutionManifest.ManifestUsage(
                        graph.usage().inputTokens(),
                        graph.usage().outputTokens(),
                        graph.usage().totalTokens(),
                        1_000),
                command.traceparent(),
                result.completedAt());
    }

    private static CommitReceipt domainReceipt(FormalResultCommit command) {
        RoomGraphCommand graph = command.request().command();
        return new CommitReceipt(
                "formal-object-001",
                graph.caseId(),
                graph.roomEpoch(),
                graph.processRevision(),
                graph.stageCode(),
                graph.stageSequence(),
                graph.actorScope().actorId(),
                graph.actorScope().actorRole(),
                graph.actorScope().audience(),
                command.manifestCommit().manifest().fencingToken(),
                command.result().resultHash());
    }

    private static AgentRunFinalizationReceipt receipt(
            FormalResultCommit command, CommitStatus status) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                command.request().agentRunId(),
                command.request().logicalRunId(),
                command.request().attemptId(),
                command.request().attemptNo(),
                command.manifestCommit().manifest().fencingToken(),
                command.result().resultHash(),
                command.manifestCommit().manifest().manifestId(),
                command.manifestCommit().manifestHash(),
                command.result().lastSequenceNo(),
                status,
                NOW);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
