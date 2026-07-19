package com.example.dispute.workflow.application;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;

import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Temporal SDK adapter with a stable logical-run workflow identity and duplicate-start recovery.
 */
@Component
public final class TemporalAgentRunV2WorkflowLauncher implements AgentRunV2WorkflowLauncher {

    private static final String WORKFLOW_ID_PREFIX = "agent-run-v2:";
    private static final int MANIFEST_IDENTIFIER_MAX_LENGTH = 128;
    private static final Pattern MANIFEST_IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private final WorkflowClient workflowClient;

    public TemporalAgentRunV2WorkflowLauncher(WorkflowClient workflowClient) {
        this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient");
    }

    @Override
    public StartReceipt start(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        String workflowId = workflowId(request.logicalRunId());
        if (request.attemptNo() > 1) {
            return updateExistingAttempt(request, workflowId);
        }
        try {
            AgentRunWorkflow workflow =
                    workflowClient.newWorkflowStub(
                            AgentRunWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue(AGENT_EXECUTION)
                                    .setWorkflowIdReusePolicy(
                                            WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                    .build());
            WorkflowExecution execution = WorkflowClient.start(workflow::run, request);
            return receipt(execution, workflowId, StartDisposition.STARTED);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            WorkflowExecution existing = requireExecution(duplicate.getExecution(), workflowId);
            return receipt(existing, workflowId, StartDisposition.ALREADY_STARTED);
        } catch (RuntimeException failure) {
            throw classify(failure);
        }
    }

    private StartReceipt updateExistingAttempt(ExecuteAgentRunRequest request, String workflowId) {
        try {
            WorkflowStub workflow = workflowClient.newUntypedWorkflowStub(workflowId);
            UpdateOptions<ExecuteAgentRunResult> update =
                    UpdateOptions.<ExecuteAgentRunResult>newBuilder()
                            .setUpdateName(AgentRunWorkflow.ATTEMPT_UPDATE)
                            .setUpdateId(request.attemptId())
                            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                            .setResultClass(ExecuteAgentRunResult.class)
                            .build();
            WorkflowUpdateHandle<ExecuteAgentRunResult> handle =
                    workflow.startUpdate(update, request);
            if (handle == null) {
                throw AgentRunV2WorkflowLaunchException.retryable(
                        "TEMPORAL_UPDATE_RECEIPT_MISSING",
                        new IllegalStateException("Temporal returned no AgentRun update handle"));
            }
            WorkflowExecution execution = requireExecution(handle.getExecution(), workflowId);
            return receipt(execution, workflowId, StartDisposition.ATTEMPT_ACCEPTED);
        } catch (RuntimeException failure) {
            throw classify(failure);
        }
    }

    public static String workflowId(String logicalRunId) {
        if (logicalRunId == null || logicalRunId.isBlank()) {
            throw new IllegalArgumentException("logicalRunId is required");
        }
        String readable = WORKFLOW_ID_PREFIX + logicalRunId;
        if (readable.length() <= MANIFEST_IDENTIFIER_MAX_LENGTH
                && MANIFEST_IDENTIFIER.matcher(readable).matches()) {
            return readable;
        }
        return WORKFLOW_ID_PREFIX + sha256(logicalRunId);
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static StartReceipt receipt(
            WorkflowExecution execution, String expectedWorkflowId, StartDisposition disposition) {
        WorkflowExecution accepted = requireExecution(execution, expectedWorkflowId);
        return new StartReceipt(expectedWorkflowId, accepted.getRunId(), disposition);
    }

    private static WorkflowExecution requireExecution(
            WorkflowExecution execution, String expectedWorkflowId) {
        if (execution == null
                || !expectedWorkflowId.equals(execution.getWorkflowId())
                || execution.getRunId() == null
                || execution.getRunId().isBlank()) {
            throw AgentRunV2WorkflowLaunchException.permanent(
                    "TEMPORAL_EXECUTION_CONFLICT",
                    new IllegalStateException(
                            "Temporal returned a conflicting AgentRun execution"));
        }
        return execution;
    }

    private static AgentRunV2WorkflowLaunchException classify(RuntimeException failure) {
        AgentRunV2WorkflowLaunchException classified =
                cause(failure, AgentRunV2WorkflowLaunchException.class);
        if (classified != null) {
            return classified;
        }
        if (cause(failure, WorkflowUpdateException.class) != null) {
            return AgentRunV2WorkflowLaunchException.permanent("TEMPORAL_UPDATE_REJECTED", failure);
        }
        if (cause(failure, WorkflowNotFoundException.class) != null) {
            return AgentRunV2WorkflowLaunchException.permanent(
                    "TEMPORAL_WORKFLOW_NOT_FOUND", failure);
        }
        if (cause(failure, IllegalArgumentException.class) != null) {
            return AgentRunV2WorkflowLaunchException.permanent("TEMPORAL_REQUEST_INVALID", failure);
        }
        return AgentRunV2WorkflowLaunchException.retryable("TEMPORAL_DISPATCH_FAILED", failure);
    }

    private static <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
