package com.example.dispute.workflow.bootstrap;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningException;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningGateway;
import com.example.dispute.workflow.infrastructure.bootstrap.SdkRoomEpochProvisioningGateway;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.failure.ApplicationFailure;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SdkRoomEpochProvisioningGatewayTest {

    @Mock private WorkflowClient workflowClient;
    @Mock private WorkflowStub workflowStub;
    @Mock private WorkflowUpdateHandle<com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt> handle;

    private SdkRoomEpochProvisioningGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SdkRoomEpochProvisioningGateway(workflowClient, properties(Duration.ofSeconds(1)));
    }

    @AfterEach
    void tearDown() {
        gateway.closeExecutor();
    }

    @Test
    void waitsForCompletedAndAcceptsStableReceiptFromAnEarlierChainRun() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        var receipt = RoomEpochProvisioningFixtures.receipt(command);
        when(workflowClient.newUntypedWorkflowStub(eq("CaseProcessWorkflow"), any()))
                .thenReturn(workflowStub);
        when(workflowStub
                        .<com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt>
                                startUpdateWithStart(any(), any(), any()))
                .thenReturn(handle);
        when(handle.getExecution())
                .thenReturn(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId(command.caseWorkflowId())
                                .setRunId("current-continue-as-new-run")
                                .build());
        when(handle.getResult()).thenReturn(receipt);

        var actual = gateway.provision(request(command));

        assertThat(actual.caseWorkflowRunId()).isEqualTo("case-first-run");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateOptions> update = ArgumentCaptor.forClass(UpdateOptions.class);
        ArgumentCaptor<Object[]> updateArguments = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<Object[]> startArguments = ArgumentCaptor.forClass(Object[].class);
        verify(workflowStub)
                .startUpdateWithStart(
                        update.capture(), updateArguments.capture(), startArguments.capture());
        assertThat(update.getValue().getUpdateName()).isEqualTo("provisionRoomEpoch");
        assertThat(update.getValue().getUpdateId()).isEqualTo(command.updateId());
        assertThat(update.getValue().getWaitForStage()).isEqualTo(COMPLETED);
        assertThat(updateArguments.getValue()).containsExactly(command);
        assertThat(startArguments.getValue()).hasSize(1);

        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient)
                .newUntypedWorkflowStub(eq("CaseProcessWorkflow"), options.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo(command.caseWorkflowId());
        assertThat(options.getValue().getWorkflowIdConflictPolicy())
                .isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING);
        assertThat(options.getValue().getWorkflowIdReusePolicy())
                .isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
    }

    @Test
    void completionTimeoutIsAnUnknownOutcomeAndRemainsRetryable() throws Exception {
        gateway.closeExecutor();
        var executor = Executors.newSingleThreadExecutor();
        var executorOccupied = new CountDownLatch(1);
        var releaseExecutor = new CountDownLatch(1);
        executor.submit(
                () -> {
                    executorOccupied.countDown();
                    releaseExecutor.await();
                    return null;
                });
        assertThat(executorOccupied.await(1, TimeUnit.SECONDS)).isTrue();
        gateway =
                new SdkRoomEpochProvisioningGateway(
                        workflowClient, Duration.ofMillis(50), executor);
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");

        try {
            assertThatThrownBy(() -> gateway.provision(request(command)))
                    .isInstanceOfSatisfying(
                            RoomEpochProvisioningException.class,
                            failure -> {
                                assertThat(failure.retryable()).isTrue();
                                assertThat(failure.errorCode())
                                        .isEqualTo("TEMPORAL_COMPLETION_TIMEOUT");
                            });
        } finally {
            releaseExecutor.countDown();
        }
    }

    @Test
    void deterministicWorkflowConflictIsPermanent() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        stubWorkflowFailure(
                ApplicationFailure.newNonRetryableFailure(
                        "update id is bound to another payload",
                        "ROOM_EPOCH_UPDATE_ID_CONFLICT"));

        assertThatThrownBy(() -> gateway.provision(request(command)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.errorCode())
                                    .isEqualTo("ROOM_EPOCH_UPDATE_ID_CONFLICT");
                        });
    }

    @Test
    void deterministicChildStartConflictIsPermanent() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        stubWorkflowFailure(
                ApplicationFailure.newNonRetryableFailure(
                        "room child workflow id is already bound to another execution",
                        "ROOM_EPOCH_CHILD_START_CONFLICT"));

        assertThatThrownBy(() -> gateway.provision(request(command)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.errorCode())
                                    .isEqualTo("ROOM_EPOCH_CHILD_START_CONFLICT");
                        });
    }

    @Test
    void transientWorkflowSwitchRemainsRetryable() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        stubWorkflowFailure(
                ApplicationFailure.newNonRetryableFailure(
                        "another room child is still switching",
                        "ROOM_EPOCH_SWITCH_IN_PROGRESS"));

        assertThatThrownBy(() -> gateway.provision(request(command)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode())
                                    .isEqualTo("ROOM_EPOCH_SWITCH_IN_PROGRESS");
                        });
    }

    private void stubWorkflowFailure(ApplicationFailure failure) {
        when(workflowClient.newUntypedWorkflowStub(eq("CaseProcessWorkflow"), any()))
                .thenReturn(workflowStub);
        when(workflowStub
                        .<com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt>
                                startUpdateWithStart(any(), any(), any()))
                .thenThrow(failure);
    }

    private static RoomEpochProvisioningGateway.ProvisioningRequest request(
            com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch command) {
        return new RoomEpochProvisioningGateway.ProvisioningRequest(
                "CaseProcessWorkflow",
                "case-control",
                command.updateId(),
                command.payloadSha256(),
                command);
    }

    private static RoomEpochBootstrapProperties properties(Duration timeout) {
        return new RoomEpochBootstrapProperties(
                true,
                2,
                2,
                Duration.ofSeconds(2),
                timeout,
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }
}
