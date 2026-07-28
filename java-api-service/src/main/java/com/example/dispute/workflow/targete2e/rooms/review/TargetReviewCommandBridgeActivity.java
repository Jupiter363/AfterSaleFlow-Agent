package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Fails closed before an untrusted case command can reach the advisory Review graph. */
public final class TargetReviewCommandBridgeActivity implements TargetReviewCommandBridgeActivities {
  public static final String BINDING_INVALID = "TARGET_REVIEW_COMMAND_BINDING_INVALID";
  private final TargetReviewCommandMaterialStore store;
  public TargetReviewCommandBridgeActivity(TargetReviewCommandMaterialStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }
  @Override public TargetReviewAgentRunTrigger bind(BindRequest input) {
    try {
      CaseCommandRef command = Objects.requireNonNull(input, "input").command();
      require(command.roomType() == RoomType.REVIEW, "room type");
      require(command.commandType() == CommandType.REVIEW_DECISION, "command type");
      var snapshot = store.readByRoute(new TargetReviewCommandMaterialStore.Route(
          command.tenantSurrogate(), command.caseId(), command.commandId(), command.roomEpoch(),
          input.roomFencingToken())).orElseThrow(() -> new IllegalArgumentException("target Review material is absent"));
      var material = snapshot.material();
      var graph = material.request().command();
      require(snapshot.admission().activationId().equals(material.activationId()), "activation admission");
      require(snapshot.admission().manifestHash().equals(material.activationManifestHash()), "manifest admission");
      require(snapshot.admission().commandHash().equals(material.commandHash()), "command hash admission");
      require(snapshot.admission().commandEnvelopeHash().equals(material.commandEnvelopeHash()), "envelope hash admission");
      require(snapshot.admission().roomFencingToken() == input.roomFencingToken(), "fence admission");
      require(material.roomFencingToken() == input.roomFencingToken(), "material fence");
      require(material.expectedProcessRevision() == command.expectedProcessRevision(), "process revision");
      require(material.expectedRoomRevision() == input.expectedRoomRevision(), "room revision");
      require(graph.commandId().equals(command.commandId()), "graph command id");
      require(graph.tenantSurrogate().equals(command.tenantSurrogate()), "graph tenant");
      require(graph.caseId().equals(command.caseId()), "graph case");
      require(graph.roomType() == RoomType.REVIEW, "graph room type");
      require(graph.roomEpoch() == command.roomEpoch(), "graph epoch");
      require(graph.processRevision() == command.expectedProcessRevision(), "graph process revision");
      require(graph.eventRef() != null, "graph event reference");
      require(graph.eventRef().uri().equals(command.payloadRef().uri()), "graph payload URI");
      require(graph.eventRef().sha256().equals(command.payloadRef().sha256()), "graph payload hash");
      return new TargetReviewAgentRunTrigger(TargetReviewAgentRunTrigger.SCHEMA_VERSION,
          material.activationId(), material.activationManifestHash(), command.commandId(), command.roomEpoch(),
          input.roomFencingToken(), command.expectedProcessRevision(),
          input.expectedRoomRevision(), material.commandHash(), material.commandEnvelopeHash(), material.request());
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), BINDING_INVALID);
    }
  }
  private static void require(boolean condition, String field) {
    if (!condition) throw new IllegalArgumentException("target Review material does not match " + field);
  }
}
