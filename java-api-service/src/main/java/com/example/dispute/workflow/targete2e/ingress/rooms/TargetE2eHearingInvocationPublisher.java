package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/** Exact system-stage Hearing invocation publisher; browser fact commands never select an operation. */
public final class TargetE2eHearingInvocationPublisher {
  private static final Map<String, String> OPERATION_STAGE = Map.of(
      "intake_questions", "INTAKE_QUESTIONS_GENERATING", "intake_synthesis", "INTAKE_SYNTHESIZING",
      "evidence_requests", "EVIDENCE_REQUESTS_GENERATING", "evidence_synthesis", "EVIDENCE_SYNTHESIZING",
      "judge_v1", "JUDGE_V1_GENERATING", "jury_review", "JURY_REVIEWING", "judge_v2", "JUDGE_V2_GENERATING");
  private final MinioTargetE2eRoomCommandPayloadPublisher publisher;
  private final TargetE2eRoomObjectIndex index;
  private final ObjectMapper mapper;

  public TargetE2eHearingInvocationPublisher(MinioTargetE2eRoomCommandPayloadPublisher publisher,
      TargetE2eRoomObjectIndex index, ObjectMapper mapper) {
    this.publisher = Objects.requireNonNull(publisher); this.index = Objects.requireNonNull(index);
    this.mapper = Objects.requireNonNull(mapper).copy();
  }

  /**
   * Publish each immutable input exactly once.  The command hash includes the returned references,
   * so command-scoped index binding intentionally happens in the separate {@link #bind} step.
   */
  public InvocationInputs publish(String commandId, String operation, String sharedBarrierReceiptHash,
      JsonNode request, JsonNode eventDocument) {
    String expectedStage = OPERATION_STAGE.get(operation);
    if (expectedStage == null || commandId == null || commandId.isBlank()) throw new IllegalArgumentException("unsupported Hearing invocation operation");
    if (sharedBarrierReceiptHash == null
        || !sharedBarrierReceiptHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Hearing shared barrier receipt hash is invalid");
    }
    if (!request.isObject() || !eventDocument.isObject()) {
      throw new IllegalArgumentException("Hearing invocation payload is invalid");
    }
    ObjectNode invocation = mapper.createObjectNode();
    invocation.put("schema_version", "target-e2e-hearing-invocation.v4");
    invocation.put("operation", operation);
    invocation.put("shared_barrier_receipt_hash", sharedBarrierReceiptHash);
    invocation.set("request", request.deepCopy());
    String artifact = "target-hearing-invocation:" + commandId + ":" + operation;
    var snapshot = publisher.publishCanonical(artifact, "HEARING", invocation);
    var event = publisher.publishCanonical("target-hearing-event:" + commandId + ":" + operation,
        "HEARING", eventDocument);
    return new InvocationInputs(snapshot, event);
  }

  public void bind(Authority authority, RoomGraphCommand command, String operation, InvocationInputs inputs) {
    String expectedStage = OPERATION_STAGE.get(operation);
    if (expectedStage == null || command.roomType().name().equals("HEARING") == false
        || !expectedStage.equals(command.stageCode()) || !command.graphKey().equals("all-rooms.target-e2e.v2")
        || !command.graphVersion().equals("target-e2e-graph.2026-08-18.1")
        || !command.checkpointSchemaVersion().equals("target-e2e-checkpoint.v2")
        || !inputs.domainSnapshotRef().equals(command.domainSnapshotRef())
        || !inputs.eventRef().equals(command.eventRef())) {
      throw new IllegalArgumentException("Hearing invocation operation does not bind its outer command");
    }
    publisher.bind(authority, command, inputs.snapshot(), TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
    publisher.bind(authority, command, inputs.event(), TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
  }

  public record InvocationInputs(MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject snapshot,
      MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject event) {
    public RoomGraphCommand.SnapshotRef domainSnapshotRef() { return snapshot.reference(); }
    public RoomGraphCommand.SnapshotRef eventRef() { return event.reference(); }
  }
}
