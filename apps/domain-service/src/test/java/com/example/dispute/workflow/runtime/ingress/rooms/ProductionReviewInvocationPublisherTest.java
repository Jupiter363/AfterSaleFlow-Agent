package com.example.dispute.workflow.runtime.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductionReviewInvocationPublisherTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final MinioProductionRoomCommandPayloadPublisher objectPublisher =
      mock(MinioProductionRoomCommandPayloadPublisher.class);
  private final ProductionReviewInvocationPublisher publisher =
      new ProductionReviewInvocationPublisher(
          objectPublisher, mock(ProductionRoomObjectIndex.class), mapper);

  @Test
  void keepsFrozenActionHashSeparateFromCanonicalDecisionEventHashAtEpochZero() {
    ObjectNode event = mapper.createObjectNode().put("decision", "APPROVE");
    String eventHash = ContractJson.sha256Hex(event);
    String actionHash = "a".repeat(64);
    RoomGraphCommand command = command(eventHash);
    var stored =
        new MinioProductionRoomCommandPayloadPublisher.PublishedObject(
            new RoomGraphCommand.SnapshotRef(
                "review-invocation:command-review-1",
                "production-runtime-review-invocation.v1",
                "urn:production-runtime:object:review-invocation:command-review-1",
                "f".repeat(64),
                1),
            "bucket",
            "key");
    when(objectPublisher.publishCanonicalOpaque(anyString(), anyString(), any(JsonNode.class)))
        .thenReturn(stored);

    publisher.publish(command, facts(event, eventHash, actionHash));

    ArgumentCaptor<JsonNode> document = ArgumentCaptor.forClass(JsonNode.class);
    verify(objectPublisher)
        .publishCanonicalOpaque(
            org.mockito.ArgumentMatchers.eq("review-invocation:command-review-1"),
            org.mockito.ArgumentMatchers.eq("REVIEW"),
            document.capture());
    JsonNode privateCommand = document.getValue().path("private_command");
    assertThat(privateCommand.path("room_epoch").asLong()).isZero();
    assertThat(privateCommand.path("action_hash").asText()).isEqualTo(actionHash);
    assertThat(privateCommand.path("event_hash").asText()).isEqualTo(eventHash);
    assertThat(privateCommand.path("event_hash").asText()).isNotEqualTo(actionHash);
  }

  @Test
  void rejectsEventAuthorityThatDoesNotMatchTheAdmittedEventReference() {
    ObjectNode event = mapper.createObjectNode().put("decision", "APPROVE");

    assertThatThrownBy(
            () -> publisher.publish(command("e".repeat(64)), facts(event, ContractJson.sha256Hex(event), "a".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  private JdbcTargetReviewInvocationFactsLoader.Facts facts(
      ObjectNode event, String eventHash, String actionHash) {
    ObjectNode packet = mapper.createObjectNode().put("action_hash", actionHash);
    return new JdbcTargetReviewInvocationFactsLoader.Facts(
        "review-task-1",
        "packet-1",
        1,
        "IN_REVIEW",
        3,
        Instant.parse("2026-07-30T12:00:00Z"),
        "b".repeat(64),
        packet,
        ContractJson.sha256Hex(packet),
        actionHash,
        event,
        eventHash,
        new JdbcTargetReviewInvocationFactsLoader.Refs(
            mapper.createArrayNode(),
            mapper.createArrayNode(),
            mapper.createArrayNode(),
            mapper.createArrayNode()));
  }

  private static RoomGraphCommand command(String eventHash) {
    return new RoomGraphCommand(
        "room-graph-command.v1",
        "command-review-1",
        "logical-run-review-1",
        "attempt-review-1",
        "legacy-default",
        "CASE_REVIEW_1",
        RoomType.REVIEW,
        0,
        "all-rooms.production-runtime.v1",
        "production-runtime-graph.2026-07-27.1",
        "production-runtime-checkpoint.v1",
        "grt.v1." + "1".repeat(32),
        new RoomGraphCommand.ActorScope(
            "reviewer-local", ActorRole.PLATFORM_REVIEWER, Audience.PLATFORM_REVIEWER, List.of()),
        7,
        "REVIEW_OUTCOME",
        1,
        new RoomGraphCommand.SnapshotRef(
            "review-invocation:command-review-1",
            "production-runtime-review-invocation.v1",
            "urn:production-runtime:object:review-invocation:command-review-1",
            "c".repeat(64),
            1),
        new RoomGraphCommand.SnapshotRef(
            "review-event-1", "target-review-decision.v1", "urn:review:event:1", eventHash, 1),
        new RoomGraphCommand.InvocationContext(
            "all-rooms-agent.production-runtime.v1",
            "prompt-v1",
            "model-v1",
            "output-v1",
            "policy-v1",
            "guardrail-v1",
            List.of(),
            "key-1",
            "nonce-1"),
        new RoomGraphCommand.RetryBudget(1, 1, 0),
        Instant.parse("2026-07-30T12:00:00Z"),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "d".repeat(64));
  }
}
