package com.example.dispute.workflow.runtime.graph;

import static com.example.dispute.workflow.runtime.graph.ProductionGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.runtime.graph.ProductionGraphTestFixtures.REGISTRY_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProductionAgentGraphCommandClientTest {

  @Test
  void mapsTheDurablyPublishedAbortToCreateNextAttempt() {
    var command = ProductionGraphTestFixtures.command();
    var request =
        new ExecuteAgentRunRequest(
            ExecuteAgentRunRequest.SCHEMA_VERSION,
            command.logicalRunId(),
            1,
            "agent-stream.v3",
            "d".repeat(64),
            null,
            false,
            0,
            command);
    List<AgentStreamEvent> durableSink = new ArrayList<>();
    var client =
        new ProductionAgentGraphCommandClient(
            ACTIVATION_ID,
            candidate -> ProductionAgentRunIdentityResolver.DurableIdentity.from(candidate, 7L),
            ProductionGraphTestFixtures.codec(),
            (envelope, binding) -> credential(),
            (sealed, visibleFields, eventSink, cancellationToken) -> {
              eventSink.accept(event(command.logicalRunId(), command.attemptId(), 0,
                  StreamEventType.ATTEMPT_STARTED, "intake.reason"));
              eventSink.accept(event(command.logicalRunId(), command.attemptId(), 1,
                  StreamEventType.ATTEMPT_ABORTED, "GRAPH_LEASE_LOST"));
              throw ProductionGraphClientException.attemptAborted("GRAPH_LEASE_LOST");
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

  @Test
  void freezesTheFinalVisibilityPolicyByTheSealedCommandRoomType() {
    Map<String, Set<String>> registryTemplate = ProductionGraphStreamVisibility.frozenPolicy();

    assertThat(capturedVisibleFields(RoomType.INTAKE, registryTemplate))
        .isSameAs(ProductionGraphStreamVisibility.frozenPolicy(RoomType.INTAKE));
    assertThat(capturedVisibleFields(RoomType.EVIDENCE, registryTemplate))
        .isSameAs(ProductionGraphStreamVisibility.frozenPolicy(RoomType.EVIDENCE))
        .containsExactly(Map.entry("evidence_turn", Set.of("room_utterance")));
    assertThat(capturedVisibleFields(RoomType.HEARING, registryTemplate))
        .isSameAs(ProductionGraphStreamVisibility.frozenPolicy(RoomType.HEARING))
        .containsEntry("hearing_evidence_requests", Set.of("public_message"))
        .containsEntry("hearing_evidence_synthesis", Set.of("public_message"))
        .containsEntry("hearing_judge_v1", Set.of("public_message"))
        .containsEntry("hearing_jury_review", Set.of("public_message"))
        .containsEntry("hearing_judge_v2", Set.of("public_message"));
    assertThat(capturedVisibleFields(RoomType.REVIEW, registryTemplate)).isEmpty();
  }

  @Test
  void rejectsAnUnregisteredVisibilityBindingBeforeCallingTheProposalClient() {
    RoomGraphCommand command = command(RoomType.EVIDENCE);
    AtomicBoolean proposalCalled = new AtomicBoolean();
    var client =
        client(
            (sealed, visibleFields, eventSink, cancellationToken) -> {
              proposalCalled.set(true);
              throw new AssertionError("proposal client must not be called");
            },
            null);

    assertThatThrownBy(
            () ->
                client.execute(
                    request(command),
                    ExecutionMode.EXECUTE_OR_RECONCILE,
                    ignored -> {},
                    new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            AgentRunExecutionException.class,
            failure ->
                assertThat(failure.errorCode()).isEqualTo("PRODUCTION_RUNTIME_GRAPH_BINDING_REJECTED"));
    assertThat(proposalCalled).isFalse();
  }

  private static Map<String, Set<String>> capturedVisibleFields(
      RoomType roomType, Map<String, Set<String>> registryTemplate) {
    RoomGraphCommand command = command(roomType);
    AtomicReference<Map<String, Set<String>>> captured = new AtomicReference<>();
    var client =
        client(
            (sealed, visibleFields, eventSink, cancellationToken) -> {
              captured.set(visibleFields);
              throw ProductionGraphClientException.attemptAborted("GRAPH_LEASE_LOST");
            },
            registryTemplate);

    assertThatThrownBy(
            () ->
                client.execute(
                    request(command),
                    ExecutionMode.EXECUTE_OR_RECONCILE,
                    ignored -> {},
                    new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            AgentRunExecutionException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("GRAPH_LEASE_LOST"));
    return captured.get();
  }

  private static ProductionAgentGraphCommandClient client(
      ProductionGraphProposalClient proposalClient,
      Map<String, Set<String>> registryTemplate) {
    return new ProductionAgentGraphCommandClient(
        ACTIVATION_ID,
        candidate -> ProductionAgentRunIdentityResolver.DurableIdentity.from(candidate, 7L),
        ProductionGraphTestFixtures.codec(),
        (envelope, binding) -> credential(),
        proposalClient,
        binding -> registryTemplate,
        binding -> REGISTRY_BINDING);
  }

  private static ExecuteAgentRunRequest request(RoomGraphCommand command) {
    return new ExecuteAgentRunRequest(
        ExecuteAgentRunRequest.SCHEMA_VERSION,
        command.logicalRunId(),
        1,
        "agent-stream.v3",
        "d".repeat(64),
        null,
        false,
        0,
        command);
  }

  private static RoomGraphCommand command(RoomType roomType) {
    ObjectNode document =
        (ObjectNode) ProductionGraphTestFixtures.MAPPER.valueToTree(
            ProductionGraphTestFixtures.command());
    document.put("room_type", roomType.name());
    document.remove("event_ref");
    document.remove("request_hash");
    document.put("request_hash", ContractJson.sha256Hex(document));
    return ProductionGraphTestFixtures.V1_CODEC.decode(
        "room-graph-command.schema.json", document, RoomGraphCommand.class);
  }

  private static AgentStreamEvent event(
      String runId,
      String attemptId,
      long sequence,
      StreamEventType type,
      String value) {
    return new AgentStreamEvent(
        "agent-stream.v3",
        runId,
        attemptId,
        sequence,
        type,
        ProductionGraphTestFixtures.command().actorScope().audience(),
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

  private static ProductionGraphEnvelopeSigner.SignedEnvelope credential() {
    Instant issuedAt = Instant.parse("2026-07-30T00:00:00Z");
    String compactJws =
        "e30.e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    return new ProductionGraphEnvelopeSigner.SignedEnvelope(
        compactJws,
        "java-invocation-es256-1",
        "target-command-jti-001",
        issuedAt,
        issuedAt.plusSeconds(45));
  }
}
