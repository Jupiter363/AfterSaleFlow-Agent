package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.function.Consumer;

/**
 * Governed Python command-ledger client below the durable Java execution gateway.
 *
 * <p>The client must key every call by thread id, command id, and request hash. In
 * {@link ExecutionMode#RECONCILE_ONLY}, it may only return an exact cached result and replay its
 * public events; a cache miss or hash conflict must fail closed without creating any graph,
 * checkpoint, provider, model, tool, or other side effect.
 */
public interface AgentGraphCommandClient {

    RoomGraphResult execute(
            ExecuteAgentRunRequest request,
            ExecutionMode mode,
            Consumer<AgentStreamEvent> eventSink,
            AgentRunCancellationToken cancellationToken);
}
