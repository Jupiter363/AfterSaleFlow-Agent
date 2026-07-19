package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;

/** Result-only Python command-ledger client; this port never opens an Agent Stream. */
public interface AgentGraphReconciliationClient {

    GraphReconcileResponse reconcile(
            ExecuteAgentRunRequest request,
            AgentRunCancellationToken cancellationToken);
}
