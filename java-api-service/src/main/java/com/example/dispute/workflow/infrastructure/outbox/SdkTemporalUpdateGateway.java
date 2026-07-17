package com.example.dispute.workflow.infrastructure.outbox;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import org.springframework.stereotype.Component;

@Component
public final class SdkTemporalUpdateGateway implements TemporalUpdateGateway {

    private final WorkflowClient workflowClient;

    public SdkTemporalUpdateGateway(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public DeliveryReceipt deliver(UpdateWithStartRequest request) {
        try {
            WorkflowOptions workflowOptions =
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(request.workflowId())
                            .setTaskQueue(request.taskQueue())
                            .setWorkflowIdConflictPolicy(
                                    WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                            .setWorkflowIdReusePolicy(
                                    WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                            .build();
            WorkflowStub workflow =
                    workflowClient.newUntypedWorkflowStub(
                            request.workflowType(), workflowOptions);
            UpdateOptions<Void> updateOptions =
                    UpdateOptions.newBuilder(Void.class)
                            .setUpdateName(
                                    CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
                            .setUpdateId(request.updateId())
                            .setWaitForStage(ACCEPTED)
                            .build();
            WorkflowUpdateHandle<Void> handle =
                    workflow.startUpdateWithStart(
                            updateOptions,
                            new Object[] {request.command()},
                            new Object[0]);
            if (handle == null
                    || handle.getExecution() == null
                    || handle.getExecution().getRunId().isBlank()) {
                throw TemporalUpdateDeliveryException.retryable(
                        "TEMPORAL_RECEIPT_INVALID",
                        "Temporal admitted the request without a usable run id",
                        null);
            }
            return new DeliveryReceipt(handle.getExecution().getRunId());
        } catch (TemporalUpdateDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private static TemporalUpdateDeliveryException classify(RuntimeException exception) {
        Status.Code statusCode = grpcStatus(exception);
        if (statusCode != null) {
            String code = "TEMPORAL_" + statusCode.name();
            if (isRetryable(statusCode)) {
                return TemporalUpdateDeliveryException.retryable(
                        code, detail(exception), exception);
            }
            return TemporalUpdateDeliveryException.permanent(
                    code, detail(exception), exception);
        }
        if (exception instanceof IllegalArgumentException) {
            return TemporalUpdateDeliveryException.permanent(
                    "TEMPORAL_REQUEST_INVALID", detail(exception), exception);
        }
        if (exception instanceof WorkflowNotFoundException) {
            return TemporalUpdateDeliveryException.permanent(
                    "TEMPORAL_NOT_FOUND", detail(exception), exception);
        }
        if (exception instanceof WorkflowUpdateException) {
            return TemporalUpdateDeliveryException.permanent(
                    "TEMPORAL_UPDATE_REJECTED", detail(exception), exception);
        }
        return TemporalUpdateDeliveryException.retryable(
                "TEMPORAL_CLIENT_FAILURE", detail(exception), exception);
    }

    private static Status.Code grpcStatus(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof StatusRuntimeException statusException) {
                return statusException.getStatus().getCode();
            }
            if (current instanceof StatusException statusException) {
                return statusException.getStatus().getCode();
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isRetryable(Status.Code code) {
        return switch (code) {
            case CANCELLED,
                    UNKNOWN,
                    DEADLINE_EXCEEDED,
                    RESOURCE_EXHAUSTED,
                    ABORTED,
                    INTERNAL,
                    UNAVAILABLE,
                    DATA_LOSS -> true;
            default -> false;
        };
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
