package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;
import java.util.Optional;

/** One Java-local transaction owner for an Activity terminal failure. */
@FunctionalInterface
public interface AgentRunTerminalFailureCommitter {

    ExecuteAgentRunResult commit(
            ExecuteAgentRunRequest request,
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult result,
            Optional<FailureTerminationReceipt> externalTermination);

    /** Legacy-only adapter retained for focused Activity tests and non-parallel callers. */
    static AgentRunTerminalFailureCommitter ledgerOnly(AgentRunLedger ledger) {
        AgentRunLedger required = Objects.requireNonNull(ledger, "ledger");
        return (request, status, result, externalTermination) -> {
            if (ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                    || externalTermination.isPresent()) {
                throw new IllegalStateException(
                        "parallel Intake failure requires the transactional terminal committer");
            }
            return required.recordAttemptFailureResult(status, result);
        };
    }
}
