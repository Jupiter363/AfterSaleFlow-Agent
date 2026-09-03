package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Validates the untrusted command reference against immutable target Evidence material. */
public final class TargetEvidenceCommandBridgeActivity implements TargetEvidenceCommandBridgeActivities {
  public static final String BINDING_INVALID = "TARGET_EVIDENCE_COMMAND_BINDING_INVALID";
  private final TargetEvidenceCommandMaterialStore materialStore;

  public TargetEvidenceCommandBridgeActivity(TargetEvidenceCommandMaterialStore materialStore) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
  }

  @Override
  public TargetEvidenceAgentRunTrigger bindEvidenceAgentRun(BindRequest input) {
    try {
      CaseCommandRef command = Objects.requireNonNull(input, "input").command();
      require(command.roomType() == RoomType.EVIDENCE, "room type");
      require(
          command.commandType() == CommandType.EVIDENCE_SUBMIT
              || command.commandType() == CommandType.EVIDENCE_OPENING,
          "command type");
      TargetEvidenceCommandMaterialStore.MaterialSnapshot snapshot = materialStore.readByRoute(
          new TargetEvidenceCommandMaterialStore.CommandLookup(command.tenantSurrogate(), command.caseId(),
              command.commandId(), command.roomEpoch(), input.roomFencingToken()))
          .orElseThrow(() -> new IllegalArgumentException("target Evidence material is absent"));
      TargetEvidenceCommandMaterial material = snapshot.material();
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
      require(graph.roomType() == RoomType.EVIDENCE, "graph room type");
      require(graph.roomEpoch() == command.roomEpoch(), "graph epoch");
      require(graph.processRevision() == command.expectedProcessRevision(), "graph process revision");
      require(graph.deadlineAt().equals(command.deadlineAt()), "graph deadline");
      require(graph.eventRef() != null, "graph event reference");
      require(graph.eventRef().uri().equals(command.payloadRef().uri()), "graph payload URI");
      require(graph.eventRef().sha256().equals(command.payloadRef().sha256()), "graph payload hash");
      if (material.evidenceAgentTurnCommand() == null) {
        require(
            TargetEvidenceCommandMaterial.LEGACY_SCHEMA_VERSION.equals(material.schemaVersion())
                && command.commandType() == CommandType.EVIDENCE_SUBMIT,
            "legacy submission material");
      } else {
        var event = material.evidenceAgentTurnCommand().contextEnvelope().currentEvent();
        if (command.commandType() == CommandType.EVIDENCE_OPENING) {
          require("ROOM_OPENING".equals(event.eventType()), "opening event type");
          require(event.messageType().name().equals("AGENT_MESSAGE"), "opening message type");
          require(event.attachmentRefs().isEmpty(), "opening attachments");
        } else {
          require("PARTY_MESSAGE".equals(event.eventType()), "submission event type");
          require(
              event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE"),
              "submission message type");
          require(!event.attachmentRefs().isEmpty(), "submission attachments");
        }
      }
      return new TargetEvidenceAgentRunTrigger("target-e2e-evidence-agent-run-trigger.v1", command.commandId(),
          command.roomEpoch(), input.roomFencingToken(), command.expectedProcessRevision(),
          input.expectedRoomRevision(), material.commandHash(), material.commandEnvelopeHash(), material.request());
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), BINDING_INVALID);
    }
  }

  private static void require(boolean condition, String field) {
    if (!condition) throw new IllegalArgumentException("target Evidence material does not match " + field);
  }
}
