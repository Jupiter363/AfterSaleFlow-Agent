package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort.FailureCommitCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits the Java AgentRun failure, V4 ERROR, Graph receipt, and V081 failure in one local
 * transaction after the external Graph owner has already terminalized.
 */
@Service
public class TransactionalAgentRunTerminalFailureCommitter
        implements AgentRunTerminalFailureCommitter {

    private final AgentRunLedger ledger;
    private final IntakeParallelFailureFinalizationPort parallelFailurePort;

    public TransactionalAgentRunTerminalFailureCommitter(
            AgentRunLedger ledger,
            IntakeParallelFailureFinalizationPort parallelFailurePort) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.parallelFailurePort =
                Objects.requireNonNull(parallelFailurePort, "parallelFailurePort");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecuteAgentRunResult commit(
            ExecuteAgentRunRequest request,
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult result,
            Optional<FailureTerminationReceipt> externalTermination) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(result, "result");
        Optional<FailureTerminationReceipt> termination =
                Objects.requireNonNull(externalTermination, "externalTermination");
        boolean parallel = ExecuteAgentRunRequest.isParallelIntakeCommand(request.command());
        if (parallel != termination.isPresent()) {
            throw new IllegalStateException(
                    "external failure termination must match the execution profile");
        }

        ExecuteAgentRunResult durable = ledger.recordAttemptFailureResult(status, result);
        if (parallel) {
            parallelFailurePort.commit(new FailureCommitCommand(
                    request, status, durable, termination.orElseThrow()));
        }
        return durable;
    }
}
