package com.example.dispute.workflow.outbox;

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
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import java.time.Instant;
import java.util.List;
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
