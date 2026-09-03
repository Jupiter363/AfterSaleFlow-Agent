package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;
import java.util.regex.Pattern;

/** Starts or resumes only the incomplete lanes of one admitted parallel Intake Frame set. */
public interface IntakeParallelFrameExecutionClient {

    FrameExecutionReceipt executeOrResume(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken);

    /** Graph-first failure terminalization; this method must not mutate Java AgentRun state. */
    FailureTerminationReceipt terminateUncommittedFailure(
            ExecuteAgentRunRequest request,
            String failureCode,
            AgentRunCancellationToken cancellationToken);

    /** Returned only after the exact three current generations are durably SEALED. */
    record FrameExecutionReceipt(
            String frameSetId, long lastSequenceNo, boolean publicOutputEmitted) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

        public FrameExecutionReceipt {
            if (frameSetId == null || !IDENTIFIER.matcher(frameSetId).matches()) {
                throw new IllegalArgumentException("frameSetId must be a bounded identifier");
            }
            if (lastSequenceNo < 0) {
                throw new IllegalArgumentException("lastSequenceNo must not be negative");
            }
        }
    }

    static IntakeParallelFrameExecutionClient required(
            IntakeParallelFrameExecutionClient client) {
        return Objects.requireNonNull(client, "frameExecutionClient");
    }

    /** Java-local technical authority conflict; callers must never terminalize Graph from it. */
    final class LocalReconciliationException extends IllegalStateException {
        private static final Pattern SAFE_CODE =
                Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");

        private final String code;

        public LocalReconciliationException(String code, String message, Throwable cause) {
            super(message, cause);
            if (code == null || !SAFE_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("local reconciliation code is invalid");
            }
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
