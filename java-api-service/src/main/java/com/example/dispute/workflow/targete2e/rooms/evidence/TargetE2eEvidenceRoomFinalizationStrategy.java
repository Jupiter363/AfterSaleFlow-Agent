package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eCrossRoomActivationVerifier;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier;
import java.util.Objects;

/** Evidence-specific authorization/evidence preparation for the shared target outer finalizer. */
public final class TargetE2eEvidenceRoomFinalizationStrategy
    implements TargetE2eRoomFinalizationStrategy {
  private final TargetEvidenceCommandMaterialStore materialStore;
  private final TargetEvidenceFinalizationEvidenceSource evidenceSource;
  private final TargetE2eFinalizationActivationPort activation;
  private final TargetE2eFinalizationRuntimeContextProvider runtime;

  public TargetE2eEvidenceRoomFinalizationStrategy(
      TargetEvidenceCommandMaterialStore materialStore,
      TargetEvidenceFinalizationEvidenceSource evidenceSource,
      TargetE2eFinalizationActivationPort activation,
      TargetE2eFinalizationRuntimeContextProvider runtime) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
    this.activation = Objects.requireNonNull(activation, "activation");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override public RoomType roomType() { return RoomType.EVIDENCE; }

  @Override
  public boolean supports(ExecuteAgentRunRequest request) {
    return request != null && request.command().roomType() == RoomType.EVIDENCE
        && TargetE2eExecutionLaneVerifier.GRAPH_KEY.equals(request.command().graphKey())
        && TargetE2eExecutionLaneVerifier.GRAPH_VERSION.equals(request.command().graphVersion())
        && TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
            request.command().checkpointSchemaVersion())
        && "target-e2e-room-proposal-source.v1".equals(
            request.command().invocationContext().outputSchemaVersion());
  }

  @Override
  public PreparedFinalization prepare(ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
    if (!supports(request) || result == null || result.graphResult() == null
        || result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED) {
      throw rejected("TARGET_E2E_EVIDENCE_FINALIZER_UNSUPPORTED", "Evidence target pins are invalid");
    }
    var graph = request.command();
    var material = materialStore.readByCommand(new TargetEvidenceCommandMaterialStore.CommandIdentity(
        graph.tenantSurrogate(), graph.caseId(), graph.commandId(), graph.roomEpoch()))
        .orElseThrow(() -> rejected("TARGET_E2E_EVIDENCE_MATERIAL_MISSING", "Evidence material is absent"));
    if (!material.material().request().equals(request)
        || !material.material().commandHash().equals(material.admission().commandHash())
        || !material.material().commandEnvelopeHash().equals(material.admission().commandEnvelopeHash())) {
      throw rejected("TARGET_E2E_EVIDENCE_MATERIAL_MISMATCH", "Evidence material is not an exact admission");
    }
    var evidence = evidenceSource.resolve(material, request, result);
    if (evidence.roomFencingToken() != material.material().roomFencingToken()
        || !evidence.isolatedDomainDbBindingHash().equals(
            material.admission().isolatedDomainDbBindingHash())) {
      throw rejected(
          "TARGET_E2E_EVIDENCE_FENCE_MISMATCH",
          "durable Evidence fence or isolated database differs from admission");
    }
    var runtimeContext = runtime.current();
    if (!runtimeContext.workflowId().equals(evidence.workflowId())
        || !runtimeContext.workflowRunId().equals(evidence.workflowRunId())
        || !runtimeContext.workflowBuildId().equals(evidence.workflowBuildId())) {
      throw rejected("TARGET_E2E_EVIDENCE_RUNTIME_MISMATCH", "durable Evidence runtime differs from Activity runtime");
    }
    var authorization = new TargetE2eFinalizationActivationPort.AuthorizationRequest(
        graph.tenantSurrogate(), graph.caseId(), evidence.roomId(), RoomType.EVIDENCE,
        request.agentRunId(), runtimeContext.workflowId(), runtimeContext.workflowRunId(),
        runtimeContext.workflowBuildId(), graph.commandId(), material.material().commandHash(),
        material.material().commandEnvelopeHash(), graph.roomEpoch(), material.material().roomFencingToken());
    var grant = TargetE2eCrossRoomActivationVerifier.requireAuthorized(
        activation.authorize(authorization),
        authorization,
        material.material().activationId(),
        material.material().activationManifestHash(),
        material.admission().isolatedDomainDbBindingHash());
    var facts = evidence.manifestFacts(material.material().roomFencingToken(), grant.activationId(), request, result);
    return new PreparedFinalization(material.material().activationManifestHash(), new ReceiptBindings(
        grant.activationId(), graph.tenantSurrogate(), graph.caseId(), RoomType.EVIDENCE, graph.roomEpoch(),
        material.material().roomFencingToken(), graph.processRevision(), graph.stageSequence(),
        material.material().commandHash(), material.material().commandEnvelopeHash(), graph.graphKey(),
        graph.graphVersion(), graph.checkpointSchemaVersion(), result.graphResult().checkpointId(),
        evidence.proposalHash(), evidence.resultEnvelopeHash(), evidence.isolatedDomainDbBindingHash(),
        evidence.completedAt()), facts);
  }

  private static TargetE2eFinalizationRejectedException rejected(String code, String message) {
    return new TargetE2eFinalizationRejectedException(code, message);
  }
}
