package com.example.dispute.workflow.targete2e.graph;

import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.REGISTRY_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TargetE2EAgentGraphCommandClientTest {

  @Test
  void mapsTheDurablyPublishedAbortToCreateNextAttempt() {
    var command = TargetE2EGraphTestFixtures.command();
    var request =
        new ExecuteAgentRunRequest(
            ExecuteAgentRunRequest.SCHEMA_VERSION,
            command.logicalRunId(),
            1,
            "agent-stream.v2",
            "d".repeat(64),
            null,
            false,
            0,
            command);
    List<AgentStreamEvent> durableSink = new ArrayList<>();
    var client =
        new TargetE2EAgentGraphCommandClient(
            ACTIVATION_ID,
            candidate -> TargetE2EAgentRunIdentityResolver.DurableIdentity.from(candidate, 7L),
            TargetE2EGraphTestFixtures.codec(),
            (envelope, binding) -> credential(),
            (sealed, visibleFields, eventSink, cancellationToken) -> {
              eventSink.accept(event(command.logicalRunId(), command.attemptId(), 0,
                  StreamEventType.ATTEMPT_STARTED, "intake.reason"));
              eventSink.accept(event(command.logicalRunId(), command.attemptId(), 1,
                  StreamEventType.ATTEMPT_ABORTED, "GRAPH_LEASE_LOST"));
              throw TargetE2EGraphClientException.attemptAborted("GRAPH_LEASE_LOST");
            },
            binding -> Map.of(),
            binding -> REGISTRY_BINDING);

    assertThatThrownBy(
            () ->
                client.execute(
                    request,
                    ExecutionMode.EXECUTE_OR_RECONCILE,
                    durableSink::add,
                    new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            AgentRunExecutionException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("GRAPH_LEASE_LOST");
              assertThat(failure.recoveryAction())
                  .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
              assertThat(failure.lastSequenceNo()).isEqualTo(1);
              assertThat(failure.publicOutputEmitted()).isFalse();
            });

    assertThat(durableSink).extracting(AgentStreamEvent::eventType)
        .containsExactly(StreamEventType.ATTEMPT_STARTED, StreamEventType.ATTEMPT_ABORTED);
    assertThat(durableSink.getLast().payload().reasonCode()).isEqualTo("GRAPH_LEASE_LOST");
  }

  private static AgentStreamEvent event(
      String runId,
      String attemptId,
      long sequence,
      StreamEventType type,
      String value) {
    return new AgentStreamEvent(
        "agent-stream.v2",
        runId,
        attemptId,
        sequence,
        type,
        TargetE2EGraphTestFixtures.command().actorScope().audience(),
        Instant.parse("2026-07-30T00:00:00Z"),
        new AgentStreamEvent.Payload(
            type == StreamEventType.ATTEMPT_STARTED ? value : null,
            null,
            null,
            null,
            type == StreamEventType.ATTEMPT_ABORTED ? value : null,
            null,
            null,
            null,
            null,
            null));
  }

  private static TargetE2EGraphEnvelopeSigner.SignedEnvelope credential() {
    Instant issuedAt = Instant.parse("2026-07-30T00:00:00Z");
    String compactJws =
        "e30.e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    return new TargetE2EGraphEnvelopeSigner.SignedEnvelope(
        compactJws,
        "java-invocation-es256-1",
        "target-command-jti-001",
        issuedAt,
        issuedAt.plusSeconds(45));
  }
}
