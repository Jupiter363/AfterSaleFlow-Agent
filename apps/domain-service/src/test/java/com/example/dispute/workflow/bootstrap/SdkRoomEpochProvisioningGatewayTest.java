package com.example.dispute.workflow.bootstrap;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
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
    void hearingProvisioningAcknowledgesDurableStartBeforeAwaitingStableCompletion()
            throws Exception {
        var hearingCommand =
                RoomEpochProvisioningFixtures.targetV2Command(
                        "EPOCH_HEARING",
                        "CASE_HEARING",
                        RoomType.HEARING,
                        "HearingRoomWorkflow");
        var hearingReceipt = RoomEpochProvisioningFixtures.receipt(hearingCommand);
        var pendingEvidenceCommand = RoomEpochProvisioningFixtures.command("EPOCH_2", "CASE_2");
        @SuppressWarnings("unchecked")
        WorkflowUpdateHandle<ProvisionRoomEpochReceipt> pendingHandle =
                (WorkflowUpdateHandle<ProvisionRoomEpochReceipt>)
                        mock(WorkflowUpdateHandle.class);
        var pendingResultStarted = new CountDownLatch(1);

        when(workflowClient.newUntypedWorkflowStub(eq("CaseProcessWorkflow"), any()))
                .thenReturn(workflowStub);
        when(workflowStub
                        .<ProvisionRoomEpochReceipt>
                                startUpdateWithStart(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            UpdateOptions<?> options = invocation.getArgument(0);
                            return hearingCommand.updateId().equals(options.getUpdateId())
                                    ? handle
                                    : pendingHandle;
                        });
        when(handle.getExecution())
                .thenReturn(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId(hearingCommand.caseWorkflowId())
                                .setRunId("current-continue-as-new-run")
                                .build());
        when(handle.getResult()).thenReturn(hearingReceipt);
        when(pendingHandle.getExecution())
                .thenReturn(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId(pendingEvidenceCommand.caseWorkflowId())
                                .setRunId("pending-case-run")
                                .build());
        when(pendingHandle.getResult())
                .thenAnswer(
                        invocation -> {
                            pendingResultStarted.countDown();
                            try {
                                new CountDownLatch(1).await();
                                return null;
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                        });

        var actual = gateway.provision(request(hearingCommand));
        var replay = gateway.provision(request(hearingCommand));

        assertThat(actual).isEqualTo(hearingReceipt).isEqualTo(replay);
        assertThat(actual.roomType()).isEqualTo(RoomType.HEARING);
        assertThat(actual.roomWorkflowId()).isEqualTo(hearingCommand.roomWorkflowId());
        assertThat(actual.roomWorkflowRunId()).isEqualTo("room-first-run");
        assertThat(actual.caseWorkflowRunId()).isEqualTo("case-first-run");

        gateway.closeExecutor();
        gateway =
                new SdkRoomEpochProvisioningGateway(
                        workflowClient,
                        Duration.ofMillis(50),
                        Executors.newSingleThreadExecutor());
        assertThatThrownBy(() -> gateway.provision(request(pendingEvidenceCommand)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode())
                                    .isEqualTo("TEMPORAL_COMPLETION_TIMEOUT");
                        });
        assertThat(pendingResultStarted.await(1, TimeUnit.SECONDS)).isTrue();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateOptions> update = ArgumentCaptor.forClass(UpdateOptions.class);
        ArgumentCaptor<Object[]> updateArguments = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<Object[]> startArguments = ArgumentCaptor.forClass(Object[].class);
        verify(workflowStub, times(3))
                .startUpdateWithStart(
                        update.capture(), updateArguments.capture(), startArguments.capture());
        assertThat(update.getAllValues())
                .allSatisfy(
                        options -> {
                            assertThat(options.getUpdateName()).isEqualTo("provisionRoomEpoch");
                            assertThat(options.getWaitForStage()).isEqualTo(ACCEPTED);
                        });
        assertThat(update.getAllValues())
                .extracting(UpdateOptions::getUpdateId)
                .containsExactly(
                        hearingCommand.updateId(),
                        hearingCommand.updateId(),
                        pendingEvidenceCommand.updateId());
        assertThat(updateArguments.getAllValues())
                .containsExactly(
                        new Object[] {hearingCommand},
                        new Object[] {hearingCommand},
                        new Object[] {pendingEvidenceCommand});
        assertThat(startArguments.getAllValues()).allSatisfy(arguments -> assertThat(arguments).hasSize(1));

        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient, times(3))
                .newUntypedWorkflowStub(eq("CaseProcessWorkflow"), options.capture());
        assertThat(options.getAllValues())
                .allSatisfy(
                        value -> {
                            assertThat(value.getWorkflowIdConflictPolicy())
                                    .isEqualTo(WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING);
                            assertThat(value.getWorkflowIdReusePolicy())
                                    .isEqualTo(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
                        });
        assertThat(options.getAllValues())
                .extracting(WorkflowOptions::getWorkflowId)
                .containsExactly(
                        hearingCommand.caseWorkflowId(),
                        hearingCommand.caseWorkflowId(),
                        pendingEvidenceCommand.caseWorkflowId());
        verify(handle, times(2)).getResult();
        verify(pendingHandle).getResult();
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
    void completedNonRetryableWorkflowSwitchIsPermanentForTheSameUpdateId() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        stubWorkflowFailure(
                ApplicationFailure.newNonRetryableFailure(
                        "another room child is still switching",
                        "ROOM_EPOCH_SWITCH_IN_PROGRESS"));

        assertThatThrownBy(() -> gateway.provision(request(command)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.errorCode())
                                    .isEqualTo("ROOM_EPOCH_SWITCH_IN_PROGRESS");
                        });
    }

    @Test
    void runtimeProvisioningFailureCannotBeRetriedWithTheCompletedUpdateId() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        stubWorkflowFailure(
                ApplicationFailure.newNonRetryableFailure(
                        "room epoch provisioning failed",
                        "ROOM_EPOCH_PROVISIONING_RUNTIME_FAILURE"));

        assertThatThrownBy(() -> gateway.provision(request(command)))
                .isInstanceOfSatisfying(
                        RoomEpochProvisioningException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.errorCode())
                                    .isEqualTo("ROOM_EPOCH_PROVISIONING_RUNTIME_FAILURE");
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
