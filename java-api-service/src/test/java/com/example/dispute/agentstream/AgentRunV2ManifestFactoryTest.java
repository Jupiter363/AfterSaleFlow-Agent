package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory.FinalizationFacts;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

class AgentRunV2ManifestFactoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-19T06:05:00Z");

    private AgentRunV2ManifestFactory factory;
    private ExecuteAgentRunRequest request;
    private ExecuteAgentRunResult result;

    @BeforeEach
    void setUp() throws Exception {
        factory = new AgentRunV2ManifestFactory(MAPPER);
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        RoomGraphResult graphResult =
                fixture("room-graph-result-valid.json", RoomGraphResult.class);
        request =
                new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        command.logicalRunId(),
                        1,
                        "agent-stream.v2",
                        "b".repeat(64),
                        null,
                        false,
                        0,
                        command);
        result =
                new ExecuteAgentRunResult(
                        ExecuteAgentRunResult.SCHEMA_VERSION,
                        request.agentRunId(),
                        request.logicalRunId(),
                        request.attemptId(),
                        request.attemptNo(),
                        ExecuteAgentRunResult.Outcome.COMPLETED,
                        graphResult,
                        graphResult.outputHash(),
                        3,
                        true,
                        null,
                        false,
                        null,
                        NOW);
    }

    @Test
    void buildsTheSameManifestAndHashFromTheSameAuditableFacts() {
        FinalizationFacts facts = facts(7, result.resultHash());

        ManifestCommit first = factory.create(request, result, facts);
        ManifestCommit replay = factory.create(request, result, facts);

        assertThat(replay).isEqualTo(first);
        assertThat(first.manifest().workflow().workflowId())
                .isEqualTo(TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId()));
        assertThat(first.manifest().model().provider()).isEqualTo("provider-a");
        assertThat(first.manifest().model().model()).isEqualTo("model-a-2026-07");
        assertThat(first.manifest().fencingToken()).isEqualTo(7);
        assertThat(first.manifest().inputs()).hasSize(1);
        assertThat(first.manifestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsAnOutputArtifactThatIsNotHashBoundToTheResult() {
        assertThatThrownBy(() -> factory.create(request, result, facts(7, "0".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output artifact hash");
    }

    @Test
    void rejectsANonPositiveFinalizationFence() {
        assertThatThrownBy(() -> facts(0, result.resultHash()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fencingToken must be positive");
    }

    FinalizationFacts facts(long fencingToken, String outputHash) {
        return new FinalizationFacts(
                fencingToken,
                "logical-key-001",
                TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId()),
                "temporal-run-001",
                "build-2026-07-19",
                "provider-a",
                "model-a-2026-07",
                "urn:manifest:" + request.attemptId(),
                new ArtifactPointer(
                        "formal-output-001",
                        result.graphResult().executionMetadata().schemaVersion(),
                        "urn:formal-output:" + request.attemptId(),
                        outputHash),
                List.of(),
                List.of("retrieval-tool.v1"),
                1_250,
                NOW);
    }

    ExecuteAgentRunRequest request() {
        return request;
    }

    ExecuteAgentRunResult result() {
        return result;
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
