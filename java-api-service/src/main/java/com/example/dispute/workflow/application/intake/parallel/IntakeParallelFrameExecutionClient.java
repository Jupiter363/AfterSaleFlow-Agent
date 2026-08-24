package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;
import java.util.regex.Pattern;

/** Starts or resumes only the incomplete lanes of one admitted parallel Intake Frame set. */
@FunctionalInterface
public interface IntakeParallelFrameExecutionClient {

    FrameExecutionReceipt executeOrResume(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
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
}
