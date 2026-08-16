package com.example.dispute.workflow.infrastructure.bootstrap;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.contract.v1.RoomEpochProvisioningProtocol;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.failure.ApplicationFailure;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class SdkRoomEpochProvisioningGateway
        implements RoomEpochProvisioningGateway {

    private final WorkflowClient workflowClient;
    private final Duration completionTimeout;
    private final ExecutorService completionExecutor;

    @Autowired
    public SdkRoomEpochProvisioningGateway(
            WorkflowClient workflowClient, RoomEpochBootstrapProperties properties) {
        this(workflowClient, properties.completionTimeout(), Executors.newVirtualThreadPerTaskExecutor());
    }

    public SdkRoomEpochProvisioningGateway(
            WorkflowClient workflowClient,
            Duration completionTimeout,
            ExecutorService completionExecutor) {
        this.workflowClient = workflowClient;
        this.completionTimeout = completionTimeout;
        this.completionExecutor = completionExecutor;
    }

    @Override
    public ProvisionRoomEpochReceipt provision(ProvisioningRequest request) {
        Future<ProvisionRoomEpochReceipt> completion =
                completionExecutor.submit(() -> provisionUntilCompleted(request));
        try {
            return completion.get(completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            completion.cancel(true);
            throw RoomEpochProvisioningException.retryable(
                    "TEMPORAL_COMPLETION_TIMEOUT",
                    "Temporal room epoch provisioning outcome is unknown",
                    exception);
        } catch (InterruptedException exception) {
            completion.cancel(true);
            Thread.currentThread().interrupt();
            throw RoomEpochProvisioningException.retryable(
                    "TEMPORAL_CLIENT_INTERRUPTED",
                    "Temporal room epoch provisioning was interrupted",
                    exception);
        } catch (ExecutionException exception) {
            throw classify(exception.getCause());
        }
    }

    private ProvisionRoomEpochReceipt provisionUntilCompleted(ProvisioningRequest request) {
        WorkflowStub workflow =
                workflowClient.newUntypedWorkflowStub(
                        request.workflowType(),
                        io.temporal.client.WorkflowOptions.newBuilder()
                                .setWorkflowId(request.command().caseWorkflowId())
                                .setTaskQueue(request.taskQueue())
                                .setWorkflowIdConflictPolicy(
                                        WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                                .setWorkflowIdReusePolicy(
                                        WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .build());
        UpdateOptions<ProvisionRoomEpochReceipt> updateOptions =
                UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                        .setUpdateName(RoomEpochProvisioningProtocol.PROVISION_ROOM_EPOCH_UPDATE)
                        .setUpdateId(request.updateId())
                        .setWaitForStage(ACCEPTED)
                        .build();
        WorkflowUpdateHandle<ProvisionRoomEpochReceipt> handle =
                workflow.startUpdateWithStart(
                        updateOptions,
                        new Object[] {request.command()},
                        new Object[] {CaseProcessCarryState.initial()});
        if (handle == null || handle.getExecution() == null) {
            throw RoomEpochProvisioningException.retryable(
                    "TEMPORAL_RECEIPT_INVALID",
                    "Temporal returned no provisioning execution",
                    null);
        }
        ProvisionRoomEpochReceipt receipt = handle.getResult();
        if (receipt == null
                || handle.getExecution().getWorkflowId().isBlank()
                || !handle.getExecution().getWorkflowId().equals(receipt.caseWorkflowId())) {
            throw RoomEpochProvisioningException.retryable(
                    "TEMPORAL_RECEIPT_INVALID",
                    "Temporal provisioning receipt does not match the workflow chain",
                    null);
        }
        return receipt;
    }

    private static RoomEpochProvisioningException classify(Throwable failure) {
        if (failure instanceof RoomEpochProvisioningException exception) {
            return exception;
        }
        ApplicationFailure applicationFailure = findApplicationFailure(failure);
        if (applicationFailure != null && applicationFailure.isNonRetryable()) {
            return RoomEpochProvisioningException.permanent(
                    applicationFailure.getType(), detail(failure), failure);
        }
        Status.Code statusCode = grpcStatus(failure);
        String code = statusCode == null ? "TEMPORAL_PROVISIONING_UNKNOWN" : "TEMPORAL_" + statusCode.name();
        return RoomEpochProvisioningException.retryable(code, detail(failure), failure);
    }

    private static ApplicationFailure findApplicationFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof ApplicationFailure applicationFailure) {
                return applicationFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Status.Code grpcStatus(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32; depth++) {
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

    private static String detail(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return failure == null
                ? "unknown Temporal provisioning failure"
                : failure.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @PreDestroy
    public void closeExecutor() {
        completionExecutor.shutdownNow();
    }
}
