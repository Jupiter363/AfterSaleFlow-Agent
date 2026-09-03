package com.example.dispute.workflow.outbox;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateDeliveryException;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.observability.TemporalSearchAttributes;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionUpdateAcceptedEventAttributes;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.failure.ApplicationFailure;
import io.temporal.common.WorkflowExecutionHistory;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SdkTemporalUpdateGatewayTest {

    @Mock private WorkflowClient workflowClient;
    @Mock private WorkflowStub workflowStub;
    @Mock private WorkflowUpdateHandle<Void> updateHandle;

    private SdkTemporalUpdateGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SdkTemporalUpdateGateway(workflowClient);
    }

    @Test
    void usesStableUpdateWithStartIdentityAndWaitsForAdmission() {
        when(workflowClient.newUntypedWorkflowStub(
                        eq("CaseProcessWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);
        when(workflowStub.startUpdateWithStart(
                        any(UpdateOptions.class),
                        any(Object[].class),
                        any(Object[].class)))
                .thenReturn(updateHandle);
        when(updateHandle.getExecution())
                .thenReturn(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId("case-process:tenant:CASE_1")
                                .setRunId("run-1")
                                .build());
        when(updateHandle.getResultAsync())
                .thenReturn(new CompletableFuture<>());

        var request = request();
        var receipt = gateway.deliver(request);

        var workflowOptions = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient)
                .newUntypedWorkflowStub(eq("CaseProcessWorkflow"), workflowOptions.capture());
        assertThat(workflowOptions.getValue().getWorkflowId())
                .isEqualTo("case-process:tenant:CASE_1");
        assertThat(workflowOptions.getValue().getTaskQueue()).isEqualTo("case-control");
        assertThat(workflowOptions.getValue().getWorkflowIdConflictPolicy())
                .isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING);
        assertThat(workflowOptions.getValue().getWorkflowIdReusePolicy())
                .isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateOptions> updateOptions =
                ArgumentCaptor.forClass(UpdateOptions.class);
        ArgumentCaptor<Object[]> updateArguments =
                ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<Object[]> startArguments =
                ArgumentCaptor.forClass(Object[].class);
        verify(workflowStub)
                .startUpdateWithStart(
                        updateOptions.capture(),
                        updateArguments.capture(),
                        startArguments.capture());
        assertThat(updateOptions.getValue().getUpdateName()).isEqualTo("acceptCommand");
        assertThat(updateOptions.getValue().getUpdateId()).isEqualTo("command-1");
        assertThat(updateOptions.getValue().getWaitForStage()).isEqualTo(ACCEPTED);
        assertThat(updateArguments.getValue()).containsExactly(request.command());
        assertThat(startArguments.getValue()).isEmpty();
        assertThat(receipt.temporalRunId()).isEqualTo("run-1");
    }

    @Test
    void classifiesTemporalUnavailabilityAsRetryable() {
        when(workflowClient.newUntypedWorkflowStub(
                        eq("CaseProcessWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);
        when(workflowStub.startUpdateWithStart(
                        any(UpdateOptions.class),
                        any(Object[].class),
                        any(Object[].class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThatThrownBy(() -> gateway.deliver(request()))
                .isInstanceOfSatisfying(
                        TemporalUpdateDeliveryException.class,
                        exception -> {
                            assertThat(exception.retryable()).isTrue();
                            assertThat(exception.errorCode())
                                    .isEqualTo("TEMPORAL_UNAVAILABLE");
                        });
    }

    @Test
    void preservesWorkflowDeadlineRejectionAsAnExplicitPermanentCode() {
        when(workflowClient.newUntypedWorkflowStub(
                        eq("CaseProcessWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);
        when(workflowStub.startUpdateWithStart(
                        any(UpdateOptions.class),
                        any(Object[].class),
                        any(Object[].class)))
                .thenThrow(
                        ApplicationFailure.newNonRetryableFailure(
                                "command deadline elapsed",
                                "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED"));

        assertThatThrownBy(() -> gateway.deliver(request()))
                .isInstanceOfSatisfying(
                        TemporalUpdateDeliveryException.class,
                        exception -> {
                            assertThat(exception.retryable()).isFalse();
                            assertThat(exception.errorCode())
                                    .isEqualTo("COMMAND_DEADLINE_EXPIRED");
                        });
    }

    @Test
    void anAcceptedUpdateFailureIsStillACompletedDelivery() {
        when(workflowClient.newUntypedWorkflowStub(
                        eq("CaseProcessWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);
        when(workflowStub.startUpdateWithStart(
                        any(UpdateOptions.class),
                        any(Object[].class),
                        any(Object[].class)))
                .thenReturn(updateHandle);
        WorkflowExecution execution =
                WorkflowExecution.newBuilder()
                        .setWorkflowId("case-process:tenant:CASE_1")
                        .setRunId("run-accepted-failure")
                        .build();
        when(updateHandle.getExecution()).thenReturn(execution);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        completion.completeExceptionally(
                ApplicationFailure.newNonRetryableFailure(
                        "handler failed after acceptance",
                        "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED"));
        when(updateHandle.getResultAsync()).thenReturn(completion);
        HistoryEvent accepted =
                HistoryEvent.newBuilder()
                        .setEventId(2)
                        .setEventType(
                                EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED)
                        .setWorkflowExecutionUpdateAcceptedEventAttributes(
                                WorkflowExecutionUpdateAcceptedEventAttributes
                                        .newBuilder()
                                        .setProtocolInstanceId("command-1"))
                        .build();
        WorkflowExecutionHistory acceptedHistory =
                new WorkflowExecutionHistory(
                        History.newBuilder()
                                .addEvents(
                                        HistoryEvent.newBuilder()
                                                .setEventId(1)
                                                .setEventType(
                                                        EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                                                .setWorkflowExecutionStartedEventAttributes(
                                                        WorkflowExecutionStartedEventAttributes
                                                                .getDefaultInstance()))
                                .addEvents(accepted)
                                .build());
        when(workflowClient.fetchHistory(
                        "case-process:tenant:CASE_1",
                        "run-accepted-failure"))
                .thenReturn(acceptedHistory);

        var receipt = gateway.deliver(request());

        assertThat(receipt.temporalRunId()).isEqualTo("run-accepted-failure");
    }

    @Test
    void writesOnlyTheApprovedTypedVisibilityAttributesOnStart() {
        gateway =
                new SdkTemporalUpdateGateway(
                        workflowClient, TemporalSearchAttributes.enabled());
        when(workflowClient.newUntypedWorkflowStub(
                        eq("CaseProcessWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);
        when(workflowStub.startUpdateWithStart(
                        any(UpdateOptions.class),
                        any(Object[].class),
                        any(Object[].class)))
                .thenReturn(updateHandle);
        when(updateHandle.getExecution())
                .thenReturn(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId("case-process:tenant:CASE_1")
                                .setRunId("run-visibility")
                                .build());
        when(updateHandle.getResultAsync())
                .thenReturn(new CompletableFuture<>());

        gateway.deliver(request());

        var options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient)
                .newUntypedWorkflowStub(eq("CaseProcessWorkflow"), options.capture());
        var visibility = options.getValue().getTypedSearchAttributes();
        assertThat(visibility.getUntypedValues().keySet())
                .extracting(key -> key.getName())
                .containsExactlyInAnyOrderElementsOf(
                        TemporalSearchAttributes.allowedKeyNames());
        assertThat(visibility.get(TemporalSearchAttributes.CASE_SURROGATE))
                .isEqualTo("CASE_1");
        assertThat(visibility.get(TemporalSearchAttributes.ROOM_TYPE))
                .isEqualTo("EVIDENCE");
    }

    private static TemporalUpdateGateway.UpdateWithStartRequest request() {
        return new TemporalUpdateGateway.UpdateWithStartRequest(
                "case-process:tenant:CASE_1",
                "CaseProcessWorkflow",
                "case-control",
                "command-1",
                command());
    }

    private static CaseCommandRef command() {
        return new CaseCommandRef(
                "case-command-ref.v1",
                "command-1",
                "tenant",
                "CASE_1",
                1,
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                0,
                new ActorRef("user-1", ActorRole.USER, List.of("case:command")),
                new PayloadRef(
                        "evidence-command.v1",
                        "urn:test:command-1",
                        "a".repeat(64),
                        10),
                0,
                Instant.parse("2026-07-17T08:00:00Z"),
                Instant.parse("2026-07-17T09:00:00Z"),
                "00-11111111111111111111111111111111-2222222222222222-01",
                "b".repeat(64));
    }
}
