package com.example.dispute.workflow.runtime.ingress.rooms;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/** Exact system-stage Hearing invocation publisher; browser fact commands never select an operation. */
public final class ProductionHearingInvocationPublisher {
  private static final Map<String, String> OPERATION_STAGE = Map.of(
      "intake_questions", "INTAKE_QUESTIONS_GENERATING", "intake_synthesis", "INTAKE_SYNTHESIZING",
      "evidence_requests", "EVIDENCE_REQUESTS_GENERATING", "evidence_synthesis", "EVIDENCE_SYNTHESIZING",
      "judge_v1", "JUDGE_V1_GENERATING", "jury_review", "JURY_REVIEWING", "judge_v2", "JUDGE_V2_GENERATING");
  private final MinioProductionRoomCommandPayloadPublisher publisher;
  private final ProductionRoomObjectIndex index;
  private final ObjectMapper mapper;

  public ProductionHearingInvocationPublisher(MinioProductionRoomCommandPayloadPublisher publisher,
      ProductionRoomObjectIndex index, ObjectMapper mapper) {
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
    invocation.put("schema_version", "production-runtime-hearing-invocation.v4");
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
        || !expectedStage.equals(command.stageCode()) || !command.graphKey().equals(TargetTypedRoomProtocol.GRAPH_KEY)
        || !TargetTypedRoomProtocol.supportsGraphVersion(command.graphVersion())
        || !command.checkpointSchemaVersion().equals(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION)
        || !inputs.domainSnapshotRef().equals(command.domainSnapshotRef())
        || !inputs.eventRef().equals(command.eventRef())) {
      throw new IllegalArgumentException("Hearing invocation operation does not bind its outer command");
    }
    publisher.bind(authority, command, inputs.snapshot(), ProductionRoomObjectIndex.Kind.COMMAND_INPUT);
    publisher.bind(authority, command, inputs.event(), ProductionRoomObjectIndex.Kind.COMMAND_INPUT);
  }

  public record InvocationInputs(MinioProductionRoomCommandPayloadPublisher.PublishedObject snapshot,
      MinioProductionRoomCommandPayloadPublisher.PublishedObject event) {
    public RoomGraphCommand.SnapshotRef domainSnapshotRef() { return snapshot.reference(); }
    public RoomGraphCommand.SnapshotRef eventRef() { return event.reference(); }
  }
}
