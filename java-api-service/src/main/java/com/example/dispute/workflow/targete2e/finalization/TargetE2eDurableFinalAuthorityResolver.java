package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Resolves the immutable result reference from the exact durable terminal protocol authority. */
public interface TargetE2eDurableFinalAuthorityResolver {

    String requireResultRef(ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
