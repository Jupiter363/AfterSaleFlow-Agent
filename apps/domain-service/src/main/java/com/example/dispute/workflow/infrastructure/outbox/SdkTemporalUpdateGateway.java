package com.example.dispute.workflow.infrastructure.outbox;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED;
import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.observability.TemporalSearchAttributes;
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
import io.temporal.failure.ApplicationFailure;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 案件命令进入 {@code CaseProcessWorkflow} 的 SDK 边界。
 *
 * <p>上游是事务后 outbox/命令应用服务；下游使用 Temporal Update-with-Start 调用根工作流的
 * {@code acceptCommand} Update。由 Temporal 记录 updateId 和 history，根工作流再把已接收命令路由到
 * 当前房间 child，因此此类不直接调用房间服务或写命令终态。
 */
@Component
public final class SdkTemporalUpdateGateway implements TemporalUpdateGateway {

    private final WorkflowClient workflowClient;
    private final TemporalSearchAttributes searchAttributes;

    public SdkTemporalUpdateGateway(WorkflowClient workflowClient) {
        this(workflowClient, TemporalSearchAttributes.disabled());
    }

    @Autowired
    public SdkTemporalUpdateGateway(
            WorkflowClient workflowClient,
            TemporalSearchAttributes searchAttributes) {
        this.workflowClient = workflowClient;
        this.searchAttributes = searchAttributes;
    }

    /**
     * 将已提交的 outbox 命令投递到案件根工作流。工作流不存在时会用 carry-state 初值创建它，存在时复用
     * 同一 workflowId；下游 {@code CaseProcessWorkflowImpl.acceptCommand} 以 updateId 去重并等待路由完成。
     */
    @Override
    public DeliveryReceipt deliver(UpdateWithStartRequest request) {
        try {
            WorkflowOptions.Builder workflowOptions =
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(request.workflowId())
                            .setTaskQueue(request.taskQueue())
                            .setWorkflowIdConflictPolicy(
                                    WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                            .setWorkflowIdReusePolicy(
                                    WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
            var visibility = searchAttributes.caseProcess(request.command());
            if (visibility.size() > 0) {
                workflowOptions.setTypedSearchAttributes(visibility);
            }
            WorkflowStub workflow =
                    workflowClient.newUntypedWorkflowStub(
                            request.workflowType(), workflowOptions.build());
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
            if (handle == null) {
                throw TemporalUpdateDeliveryException.retryable(
                        "TEMPORAL_RECEIPT_INVALID",
                        "Temporal admitted the request without a usable run id",
                        null);
            }
            if (handle.getExecution() == null
                    || handle.getExecution().getRunId().isBlank()) {
                throw TemporalUpdateDeliveryException.retryable(
                        "TEMPORAL_RECEIPT_INVALID",
                        "Temporal admitted the request without a usable run id",
                        null);
            }
            CompletableFuture<Void> completion = handle.getResultAsync();
            if (completion.isCompletedExceptionally()) {
                try {
                    completion.join();
                } catch (RuntimeException completionFailure) {
                    if (!wasAccepted(
                            handle.getExecution(), request.updateId())) {
                        throw completionFailure;
                    }
                }
            }
            return new DeliveryReceipt(handle.getExecution().getRunId());
        } catch (TemporalUpdateDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private boolean wasAccepted(
            io.temporal.api.common.v1.WorkflowExecution execution,
            String updateId) {
        return workflowClient
                .fetchHistory(execution.getWorkflowId(), execution.getRunId())
                .getEvents()
                .stream()
                .filter(
                        event ->
                                event.getEventType()
                                        == EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED)
                .anyMatch(
                        event ->
                                updateId.equals(
                                        event
                                                .getWorkflowExecutionUpdateAcceptedEventAttributes()
                                                .getProtocolInstanceId()));
    }

    private static TemporalUpdateDeliveryException classify(RuntimeException exception) {
        ApplicationFailure applicationFailure = applicationFailure(exception);
        if ((applicationFailure != null
                        && "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED"
                                .equals(applicationFailure.getType()))
                || containsFailureMarker(
                        exception, "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED")) {
            return TemporalUpdateDeliveryException.permanent(
                    "COMMAND_DEADLINE_EXPIRED", detail(exception), exception);
        }
        if (workflowUpdateException(exception) != null) {
            return TemporalUpdateDeliveryException.permanent(
                    "TEMPORAL_UPDATE_REJECTED", detail(exception), exception);
        }
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

    private static ApplicationFailure applicationFailure(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof ApplicationFailure failure) {
                return failure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static WorkflowUpdateException workflowUpdateException(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof WorkflowUpdateException updateException) {
                return updateException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean containsFailureMarker(Throwable exception, String marker) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 32; depth++) {
            String message = current.getMessage();
            if (message != null && message.contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
