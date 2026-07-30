package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * Rebuilds an Evidence finalization request from immutable admitted material.
 *
 * <p>This resolver deliberately does not infer authorization from the graph result. The matching
 * V050 row is the only source for the activation, command, envelope, and fence bindings used by
 * the room-owned domain writer. The shared outer finalizer remains responsible for target receipt
 * append and command completion.
 */
public final class TargetEvidenceFinalizationRequestResolver {
  private final TargetEvidenceCommandMaterialStore materialStore;
  private final ObjectMapper objectMapper;

  public TargetEvidenceFinalizationRequestResolver(
      TargetEvidenceCommandMaterialStore materialStore, ObjectMapper objectMapper) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  public TargetEvidenceFinalizationRequest resolve(CommitCommand command) {
    Objects.requireNonNull(command, "command");
    var request = command.request();
    var graph = request.command();
    if (graph.roomType() != RoomType.EVIDENCE) {
      throw new IllegalArgumentException("target Evidence finalization requires an Evidence command");
    }
    var snapshot = materialStore.readByRoute(new TargetEvidenceCommandMaterialStore.CommandLookup(
        graph.tenantSurrogate(), graph.caseId(), graph.commandId(), graph.roomEpoch(),
        command.manifest().fencingToken()))
        .orElseThrow(() -> new IllegalStateException("target Evidence admitted material is absent"));
    var material = snapshot.material();
    var admission = snapshot.admission();
    require(material.request().equals(request), "stored AgentRun request");
    require(material.activationId().equals(admission.activationId()), "activation id");
    require(material.activationManifestHash().equals(admission.manifestHash()), "activation manifest");
    require(material.commandHash().equals(admission.commandHash()), "command hash admission");
    require(material.commandEnvelopeHash().equals(admission.commandEnvelopeHash()), "envelope hash admission");
    require(material.roomFencingToken() == admission.roomFencingToken(), "fence admission");
    require(material.expectedProcessRevision() == graph.processRevision(), "process revision");
    require(material.expectedRoomRevision() >= 0, "room revision");
    require(material.commandHash().equals(ContractJson.sha256Hex(objectMapper.valueToTree(graph))),
        "canonical graph command hash");
    require(command.result().outcome()
        == com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult.Outcome.COMPLETED,
        "completed result");
    require(command.result().graphResult() != null, "graph result");
    require(command.result().resultHash().equals(command.result().graphResult().outputHash()), "result hash");
    require(graph.commandId().equals(command.result().graphResult().commandId()), "result command id");
    require(graph.graphKey().equals(command.result().graphResult().graphKey()), "result graph key");
    require(graph.graphVersion().equals(command.result().graphResult().graphVersion()), "result graph version");

    String operationId = "target-e2e-evidence:"
        + ContractJson.sha256Hex(objectMapper.valueToTree(java.util.List.of(
            material.activationId(), graph.commandId(), command.result().resultHash()))).substring(0, 40);
    return new TargetEvidenceFinalizationRequest(
        material.executionLane(), material.activationId(), material.activationManifestHash(),
        snapshot.admissionId(), admission.isolatedDomainDbBindingHash(), material.commandHash(),
        material.commandEnvelopeHash(), material.caseCommandRequestHash(), material.roomFencingToken(),
        material.expectedProcessRevision(), material.expectedRoomRevision(), operationId,
        graph.stageCode(), graph.stageSequence(),
        graph.actorScope().actorId(), graph.actorScope().actorRole(), graph.actorScope().audience(), command);
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new IllegalStateException("target Evidence finalization binding differs at " + field);
    }
  }
}
