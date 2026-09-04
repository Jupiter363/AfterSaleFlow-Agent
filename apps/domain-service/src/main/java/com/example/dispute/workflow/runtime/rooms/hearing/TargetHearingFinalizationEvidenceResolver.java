package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Reloads the immutable admitted-material, Graph and runtime-fence evidence for Hearing. */
@FunctionalInterface
public interface TargetHearingFinalizationEvidenceResolver {
  TargetHearingFinalizationEvidence resolve(
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result,
      TargetHearingCommandMaterialStore.Snapshot material);
}
