package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntakeParallelV4DurableFinalAuthorityResolverTest {

    private static final String RUN_ID = "target-intake-run:test";
    private static final String ATTEMPT_ID = "target-intake-attempt:test:1";
    private static final String COMMAND_ID = "intake-message:test";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final long FINAL_SEQUENCE = 20;
    private static final Instant FINAL_AT = Instant.parse("2026-08-27T10:00:00Z");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final ObjectMapper READY_ARTIFACT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    private static final RoomGraphResult GRAPH_RESULT =
            TargetE2eFinalizationFixture.valid().result().graphResult();
    private static final String RESULT_HASH = GRAPH_RESULT.outputHash();

    @Test
    void exactV4FinalJoinsItsPrivateReadyResultReferenceAcrossCanonicalMapperProfiles() {
        ExecuteAgentRunRequest request = parallelRequest();
        ExecuteAgentRunResult result = completedResult();
        IntakeParallelAssemblyStore assemblyStore = mock(IntakeParallelAssemblyStore.class);
        ReadyArtifact artifact = artifact(result.graphResult());
        when(assemblyStore.loadReady(any())).thenReturn(Optional.of(artifact));
        TargetE2eV4FinalAuthoritySource source = (runId, attemptId, sequenceNo) -> Optional.of(
                finalAuthority(result.resultHash()));

        var resolver = new IntakeParallelV4DurableFinalAuthorityResolver(
                source, assemblyStore, MAPPER);

        assertThat(resolver.requireResultRef(request, result)).isEqualTo(artifact.resultRef());
    }

    @Test
    void changedPublicFinalHashIsRejectedBeforeReadyCanAuthorizeIt() {
        ExecuteAgentRunRequest request = parallelRequest();
        ExecuteAgentRunResult result = completedResult();
        IntakeParallelAssemblyStore assemblyStore = mock(IntakeParallelAssemblyStore.class);
        TargetE2eV4FinalAuthoritySource source = (runId, attemptId, sequenceNo) -> Optional.of(
                finalAuthority("c".repeat(64)));

        var resolver = new IntakeParallelV4DurableFinalAuthorityResolver(
                source, assemblyStore, MAPPER);

        assertThatThrownBy(() -> resolver.requireResultRef(request, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable final conflicts");
        verify(assemblyStore, never()).loadReady(any());
    }

    @Test
    void routingUsesOnlyTheExplicitParallelProfile() {
        TargetE2eDurableFinalAuthorityResolver legacy = mock(
                TargetE2eDurableFinalAuthorityResolver.class);
        TargetE2eDurableFinalAuthorityResolver parallel = mock(
                TargetE2eDurableFinalAuthorityResolver.class);
        ExecuteAgentRunRequest request = parallelRequest();
        ExecuteAgentRunResult result = completedResult();
        when(parallel.requireResultRef(request, result)).thenReturn("urn:parallel:result");

        var routing = new RoutingTargetE2eDurableFinalAuthorityResolver(legacy, parallel);

        assertThat(routing.requireResultRef(request, result)).isEqualTo("urn:parallel:result");
        verify(parallel).requireResultRef(request, result);
        verify(legacy, never()).requireResultRef(any(), any());
    }

    @Test
    void executionLaneProtocolDiscriminatorKeepsV3AdjacentToParallelV4() {
        ExecuteAgentRunRequest parallel = parallelRequest();
        ExecuteAgentRunRequest legacy = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand legacyCommand = mock(RoomGraphCommand.class);
        when(legacy.command()).thenReturn(legacyCommand);
        when(legacyCommand.isExactParallelIntakeProfile()).thenReturn(false);

        assertThat(TargetE2eExecutionLaneVerifier.expectedAgentRunProtocol(parallel))
                .isEqualTo("agent-stream.v4");
        assertThat(TargetE2eExecutionLaneVerifier.expectedAgentRunProtocol(legacy))
                .isEqualTo("agent-stream.v3");
    }

    private static ExecuteAgentRunRequest parallelRequest() {
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand command = mock(RoomGraphCommand.class);
        when(request.command()).thenReturn(command);
        when(request.streamProtocol()).thenReturn("agent-stream.v4");
        when(request.agentRunId()).thenReturn(RUN_ID);
        when(request.attemptId()).thenReturn(ATTEMPT_ID);
        when(command.isExactParallelIntakeProfile()).thenReturn(true);
        when(command.commandId()).thenReturn(COMMAND_ID);
        when(command.requestHash()).thenReturn(REQUEST_HASH);
        when(command.actorScope()).thenReturn(new RoomGraphCommand.ActorScope(
                "user-local", ActorRole.USER, Audience.USER, List.of("intake:answer")));
        return request;
    }

    private static ExecuteAgentRunResult completedResult() {
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        when(result.resultHash()).thenReturn(RESULT_HASH);
        when(result.lastSequenceNo()).thenReturn(FINAL_SEQUENCE);
        when(result.graphResult()).thenReturn(GRAPH_RESULT);
        return result;
    }

    private static TargetE2eV4FinalAuthoritySource.FinalAuthority finalAuthority(
            String resultHash) {
        AgentStreamEventV4 event = new AgentStreamEventV4(
                "agent-stream.v4",
                RUN_ID,
                ATTEMPT_ID,
                FINAL_SEQUENCE,
                AgentStreamEventV4.EventType.FINAL,
                Audience.USER,
                FINAL_AT,
                AgentStreamEventV4.Payload.finalPayload("receipt-final", resultHash));
        return new TargetE2eV4FinalAuthoritySource.FinalAuthority(
                event, "event-final", ContractJson.sha256Hex(MAPPER.valueToTree(event)),
                FINAL_SEQUENCE, "user-local");
    }

    private static ReadyArtifact artifact(RoomGraphResult graph) {
        byte[] graphBytes =
                ContractJson.canonicalize(READY_ARTIFACT_MAPPER.valueToTree(graph));
        String proposalHash = "d".repeat(64);
        return new ReadyArtifact(
                "e".repeat(64),
                "intake.proposal." + proposalHash.substring(0, 32),
                "urn:target-e2e:proposal:intake:" + proposalHash,
                proposalHash,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "profile-manifest-v1",
                "intake.graph-result." + RESULT_HASH.substring(0, 32),
                "urn:target-e2e:result:intake:" + RESULT_HASH,
                RESULT_HASH,
                graphBytes,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "f".repeat(64),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "1".repeat(64),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "2".repeat(64),
                "checkpoint-ns-v1",
                "3".repeat(64),
                "no-tools-v1");
    }
}
