package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/**
 * Java-local companion for a Graph-first parallel failure terminalization.
 *
 * <p>The caller owns the surrounding transaction that also writes the AgentRun failure and V4
 * ERROR. Implementations may persist only the immutable Graph receipt and V081 technical failure;
 * they must not write Intake business state.
 */
public interface IntakeParallelFailureFinalizationPort {

    FailureCommitReceipt commit(FailureCommitCommand command);

    record FailureCommitCommand(
            ExecuteAgentRunRequest request,
            AgentRunAttemptStatus attemptStatus,
            ExecuteAgentRunResult durableResult,
            FailureTerminationReceipt graphReceipt) {

        public FailureCommitCommand {
            request = Objects.requireNonNull(request, "request");
            attemptStatus = Objects.requireNonNull(attemptStatus, "attemptStatus");
            durableResult = Objects.requireNonNull(durableResult, "durableResult");
            graphReceipt = Objects.requireNonNull(graphReceipt, "graphReceipt");
            if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                    || !"agent-stream.v4".equals(request.streamProtocol())
                    || durableResult.outcome() != ExecuteAgentRunResult.Outcome.FAILED
                    || durableResult.recoveryAction()
                            != AgentRunRecoveryAction.FAIL_LOGICAL_RUN
                    || durableResult.retryable()
                    || !request.agentRunId().equals(durableResult.agentRunId())
                    || !request.logicalRunId().equals(durableResult.logicalRunId())
                    || !request.attemptId().equals(durableResult.attemptId())
                    || request.attemptNo() != durableResult.attemptNo()
                    || attemptStatus
                            != (durableResult.publicOutputEmitted()
                                    ? AgentRunAttemptStatus.ABORTED
                                    : AgentRunAttemptStatus.FAILED)) {
                throw new IllegalArgumentException(
                        "parallel failure finalization authority is invalid");
            }
        }
    }

    record FailureCommitReceipt(
            String frameSetId,
            String graphReceiptId,
            String graphReceiptHash,
            String failureCode,
            boolean inserted,
            long frameSetVersion) {

        public FailureCommitReceipt {
            Objects.requireNonNull(frameSetId, "frameSetId");
            Objects.requireNonNull(graphReceiptId, "graphReceiptId");
            Objects.requireNonNull(graphReceiptHash, "graphReceiptHash");
            Objects.requireNonNull(failureCode, "failureCode");
            if (frameSetVersion < 0) {
                throw new IllegalArgumentException("frameSetVersion must not be negative");
            }
        }
    }

    final class FailureCommitConflictException extends IllegalStateException {
        private final String code;

        public FailureCommitConflictException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
