package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRejectedException;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionCrossRoomActivationVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * Fail-closed HEARING strategy for the single target multi-room finalizer.
 *
 * <p>Only one of the four explicit Hearing graph families can be finalized. The strategy reads
 * the immutable material using the command route, then requires the evidence resolver to bind the
 * material, proposal, runtime facts, fence and isolated Domain database identity before exposing
 * the generic receipt facts.
 */
public final class TargetHearingRoomFinalizationStrategy
    implements ProductionRoomFinalizationStrategy {

  private static final String SOURCE_SCHEMA = "production-runtime-room-proposal-source.v2";

  private final TargetHearingCommandMaterialStore materialStore;
  private final TargetHearingFinalizationEvidenceResolver evidenceResolver;
  private final ProductionFinalizationActivationPort activation;
  private final ProductionFinalizationRuntimeContextProvider runtime;
  private final ObjectMapper objectMapper;

  public TargetHearingRoomFinalizationStrategy(
      TargetHearingCommandMaterialStore materialStore,
      TargetHearingFinalizationEvidenceResolver evidenceResolver,
      ProductionFinalizationActivationPort activation,
      ProductionFinalizationRuntimeContextProvider runtime,
      ObjectMapper objectMapper) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.evidenceResolver = Objects.requireNonNull(evidenceResolver, "evidenceResolver");
    this.activation = Objects.requireNonNull(activation, "activation");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public RoomType roomType() {
    return RoomType.HEARING;
  }

  @Override
  public boolean supports(ExecuteAgentRunRequest request) {
    if (request == null || request.command().roomType() != RoomType.HEARING) {
      return false;
    }
    return ProductionExecutionLaneVerifier.GRAPH_KEY.equals(request.command().graphKey())
        && ProductionExecutionLaneVerifier.GRAPH_VERSION.equals(request.command().graphVersion())
        && ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
            request.command().checkpointSchemaVersion())
        && SOURCE_SCHEMA.equals(request.command().invocationContext().outputSchemaVersion());
  }

  @Override
  public PreparedFinalization prepare(
      ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
    if (!supports(request) || result == null || result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
        || result.graphResult() == null) {
      throw rejected("PRODUCTION_RUNTIME_HEARING_FINALIZER_UNSUPPORTED",
          "target Hearing finalization requires an exact Hearing graph family");
    }
    var command = request.command();
    TargetHearingCommandMaterialStore.Snapshot material = materialStore.readByCommand(
        new TargetHearingCommandMaterialStore.CommandRoute(command.tenantSurrogate(), command.caseId(),
            command.commandId(), command.roomEpoch()))
        .orElseThrow(() -> rejected("PRODUCTION_RUNTIME_HEARING_FINALIZATION_MATERIAL_MISSING",
            "target Hearing command material was not admitted"));
    requireExactMaterial(request, material);
    TargetHearingFinalizationEvidence evidence = Objects.requireNonNull(
        evidenceResolver.resolve(request, result, material), "target Hearing finalization evidence");
    var runtimeContext = Objects.requireNonNull(runtime.current(), "target Hearing runtime context");
    if (!runtimeContext.workflowId().equals(evidence.workflowId())
        || !runtimeContext.workflowRunId().equals(evidence.workflowRunId())
        || !runtimeContext.workflowBuildId().equals(evidence.workflowBuildId())) {
      throw rejected("PRODUCTION_RUNTIME_HEARING_RUNTIME_MISMATCH",
          "durable Hearing runtime facts differ from the Activity runtime");
    }
    var authorization = new ProductionFinalizationActivationPort.AuthorizationRequest(
        command.tenantSurrogate(), command.caseId(), evidence.roomId(), RoomType.HEARING,
        request.agentRunId(), runtimeContext.workflowId(), runtimeContext.workflowRunId(),
        runtimeContext.workflowBuildId(), command.commandId(), material.admission().commandHash(),
        material.admission().commandEnvelopeHash(), command.roomEpoch(),
        material.admission().roomFencingToken());
    var grant = ProductionCrossRoomActivationVerifier.requireAuthorized(
        activation.authorize(authorization),
        authorization,
        material.admission().activationId(),
        material.admission().manifestHash(),
        material.admission().isolatedDomainDbBindingHash());
    requireExactEvidence(request, result, material, evidence);
    var graph = result.graphResult();
    return new PreparedFinalization(evidence.activationManifestHash(), new ReceiptBindings(
        grant.activationId(), command.tenantSurrogate(), command.caseId(), RoomType.HEARING,
        command.roomEpoch(), evidence.roomFencingToken(), command.processRevision(),
        command.stageSequence(), evidence.commandHash(), evidence.commandEnvelopeHash(),
        command.graphKey(), command.graphVersion(), command.checkpointSchemaVersion(),
        graph.checkpointId(), evidence.proposalHash(), evidence.resultEnvelopeHash(),
        evidence.isolatedDomainDbBindingHash(), evidence.committedAt()), evidence.manifestFacts());
  }

  private void requireExactMaterial(
      ExecuteAgentRunRequest request, TargetHearingCommandMaterialStore.Snapshot material) {
    var command = request.command();
    var admission = material.admission();
    if (!material.material().request().equals(request)
        || !admission.tenantSurrogate().equals(command.tenantSurrogate())
        || !admission.caseId().equals(command.caseId())
        || !admission.commandId().equals(command.commandId())
        || admission.roomEpoch() != command.roomEpoch()
        || !admission.commandHash().equals(ContractJson.sha256Hex(objectMapper.valueToTree(command)))) {
      throw rejected("PRODUCTION_RUNTIME_HEARING_FINALIZATION_MATERIAL_MISMATCH",
          "admitted Hearing material conflicts with the AgentRun request");
    }
  }

  private static void requireExactEvidence(
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result,
      TargetHearingCommandMaterialStore.Snapshot material,
      TargetHearingFinalizationEvidence evidence) {
    var command = request.command();
    var graph = result.graphResult();
    if (!evidence.activationId().equals(material.admission().activationId())
        || !evidence.activationManifestHash().equals(material.admission().manifestHash())
        || !evidence.isolatedDomainDbBindingHash().equals(material.admission().isolatedDomainDbBindingHash())
        || !evidence.commandHash().equals(material.admission().commandHash())
        || !evidence.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash())
        || evidence.roomFencingToken() != material.admission().roomFencingToken()
        || !evidence.materialSha256().equals(material.materialSha256())
        || graph.artifactOperations().size() != 1
        || graph.artifactOperations().getFirst().operation() != ArtifactOperationType.PROPOSE_CREATE
        || !graph.artifactOperations().getFirst().artifact().equals(evidence.proposal())
        || !graph.commandId().equals(command.commandId())
        || !graph.logicalRunId().equals(request.logicalRunId())
        || !graph.attemptId().equals(request.attemptId())
        || !graph.graphKey().equals(command.graphKey())
        || !graph.graphVersion().equals(command.graphVersion())
        || !graph.executionMetadata().schemaVersion().equals(
            command.invocationContext().outputSchemaVersion())
        || !evidence.manifestFacts().output().sha256().equals(result.resultHash())
        || evidence.manifestFacts().fencingToken() != material.admission().roomFencingToken()
        || !evidence.manifestFacts().finalizedAt().equals(evidence.committedAt())) {
      throw rejected("PRODUCTION_RUNTIME_HEARING_FINALIZATION_EVIDENCE_MISMATCH",
          "Hearing finalization evidence is not an exact durable binding");
    }
  }

  private static ProductionFinalizationRejectedException rejected(String code, String message) {
    return new ProductionFinalizationRejectedException(code, message);
  }
}
