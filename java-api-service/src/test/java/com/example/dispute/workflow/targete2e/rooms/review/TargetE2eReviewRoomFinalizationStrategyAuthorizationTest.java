package com.example.dispute.workflow.targete2e.rooms.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory.FinalizationFacts;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.Decision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetE2eReviewRoomFinalizationStrategyAuthorizationTest {

  @Test
  void reauthorizesTheExactDurableReviewRouteBeforePreparingACommit() {
    String manifestHash = "a".repeat(64);
    String databaseHash = "b".repeat(64);
    String commandHash = "c".repeat(64);
    String envelopeHash = "d".repeat(64);
    ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
    ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
    RoomGraphCommand graph = mock(RoomGraphCommand.class);
    when(request.command()).thenReturn(graph);
    when(request.agentRunId()).thenReturn("RUN_REVIEW_1");
    when(graph.roomType()).thenReturn(RoomType.REVIEW);
    when(graph.graphKey()).thenReturn(TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY);
    when(graph.graphVersion()).thenReturn(
        com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier.GRAPH_VERSION);
    when(graph.checkpointSchemaVersion()).thenReturn(
        com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION);
    when(graph.tenantSurrogate()).thenReturn("tenant-review");
    when(graph.caseId()).thenReturn("CASE_REVIEW_1");
    when(graph.commandId()).thenReturn("COMMAND_REVIEW_1");
    when(graph.roomEpoch()).thenReturn(4L);

    TargetReviewFinalizationRequest resolved = new TargetReviewFinalizationRequest(
        TargetReviewCommandMaterial.TARGET_LANE,
        "p9act.v1." + "1".repeat(32),
        manifestHash,
        databaseHash,
        "ROOM_REVIEW_1",
        9,
        "ADMISSION_REVIEW_1",
        commandHash,
        envelopeHash,
        "e".repeat(64),
        "f".repeat(64),
        "provider-review",
        "model-review",
        request,
        result,
        mock(TargetReviewHumanDecisionReceipt.class));
    TargetReviewFinalizationRequestResolver resolver = mock(TargetReviewFinalizationRequestResolver.class);
    when(resolver.resolve(request, result)).thenReturn(resolved);
    TargetReviewFinalizationFactsProvider factsProvider = (ignored, ignoredRequest, ignoredResult) ->
        new FinalizationFacts(
            9,
            "review-logical-key",
            "agent-run-v2:RUN_REVIEW_1",
            "workflow-run-review-1",
            "target-review-build",
            "provider-review",
            "model-review",
            "urn:target-e2e:review-manifest",
            new ArtifactPointer(
                "OUTPUT_REVIEW_1", "review-output.v1", "urn:target-e2e:review-output", "9".repeat(64)),
            List.of(),
            List.of(),
            10,
            Instant.parse("2026-07-29T00:30:00Z"));
    TargetE2eFinalizationActivationPort activation = mock(TargetE2eFinalizationActivationPort.class);
    when(activation.authorize(org.mockito.ArgumentMatchers.any()))
        .thenReturn(TargetE2eFinalizationActivationPort.AuthorizationDecision.denied(Decision.REVOKED));
    var strategy = new TargetE2eReviewRoomFinalizationStrategy(resolver, factsProvider, activation);

    assertThatThrownBy(() -> strategy.prepare(request, result))
        .isInstanceOf(TargetE2eFinalizationRejectedException.class)
        .hasMessageContaining("no current allowed activation");
    verify(activation).authorize(argThat(authorization ->
        authorization.roomType() == RoomType.REVIEW
            && authorization.roomId().equals("ROOM_REVIEW_1")
            && authorization.workflowId().equals("agent-run-v2:RUN_REVIEW_1")
            && authorization.workflowRunId().equals("workflow-run-review-1")
            && authorization.workflowBuildId().equals("target-review-build")
            && authorization.commandHash().equals(commandHash)
            && authorization.commandEnvelopeHash().equals(envelopeHash)
            && authorization.roomFencingToken() == 9));
  }
}
