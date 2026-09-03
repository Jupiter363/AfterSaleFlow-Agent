package com.example.dispute.workflow.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlStart;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomControlWorkflowTest {

    private static final String TENANT = "tenant-room-control";
    private static final String CASE_ID = "CASE_RoomControl";
    private static final String WORKFLOW_ID =
            CaseProcessWorkflowProtocol.roomWorkflowId(
                    CASE_ID, RoomType.EVIDENCE, 3);
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        environment =
                TestWorkflowEnvironment.newInstance(
                        TestEnvironmentOptions.newBuilder()
                                .setInitialTime(NOW)
                                .build());
        Worker worker =
                environment.newWorker(
                        CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(RoomControlWorkflowImpl.class);
        environment.start();
        client = environment.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void twentyFourHourContinueAsNewCarriesCountersAndDeduplicationState() {
        RoomControlWorkflow workflow =
                client.newWorkflowStub(
                        RoomControlWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(WORKFLOW_ID)
                                .setTaskQueue(
                                        CaseProcessWorkflowProtocol
                                                .ROOM_CONTROL_TASK_QUEUE)
                                .build());
        WorkflowClient.start(
                workflow::run,
                new RoomControlStart(
                        "room-control-start.v1",
                        TENANT,
                        CASE_ID,
                        RoomType.EVIDENCE,
                        3,
                        "case-process:tenant-room-control:CASE_RoomControl",
                        1,
                        1));
        workflow.commandAccepted(command());
        workflow.domainEventCommitted(event());
        RoomControlSnapshot before =
                awaitState(
                        workflow,
                        snapshot ->
                                snapshot.processedCommandCount() == 1
                                        && snapshot.processedEventCount() == 1);

        environment.sleep(Duration.ofHours(24));

        RoomControlSnapshot continued =
                awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
        assertThat(continued.workflowRunId()).isNotEqualTo(before.workflowRunId());
        assertThat(continued.processedCommandCount()).isEqualTo(1);
        assertThat(continued.processedEventCount()).isEqualTo(1);
        assertThat(continued.recentCommandIds())
                .containsExactly("command-room-control-1");
        assertThat(continued.recentEventIds())
                .containsExactly("event-room-control-1");

        workflow.commandAccepted(command());
        workflow.domainEventCommitted(event());
        workflow.commandAccepted(command(2));
        workflow.domainEventCommitted(event(2));
        RoomControlSnapshot afterDuplicates =
                awaitState(
                        workflow,
                        snapshot ->
                                snapshot.processedCommandCount() == 2
                                        && snapshot.processedEventCount() == 2);
        assertThat(afterDuplicates.recentCommandIds())
                .containsExactly("command-room-control-1", "command-room-control-2");
        assertThat(afterDuplicates.recentEventIds())
                .containsExactly("event-room-control-1", "event-room-control-2");
    }

    @Test
    void fullSignalQueueAndElapsedRunTimerYieldUntilHandlersDrain() {
        String workflowId = WORKFLOW_ID + "-queue-race";
        RoomControlWorkflow workflow =
                client.newWorkflowStub(
                        RoomControlWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(
                                        CaseProcessWorkflowProtocol
                                                .ROOM_CONTROL_TASK_QUEUE)
                                .build());
        WorkflowClient.start(
                workflow::run,
                new RoomControlStart(
                        "room-control-start.v1",
                        TENANT,
                        CASE_ID,
                        RoomType.EVIDENCE,
                        3,
                        "case-process:tenant-room-control:CASE_RoomControl",
                        1,
                        1));
        awaitState(workflow, snapshot -> snapshot.runGeneration() == 0);

        environment.getWorkerFactory().suspendPolling();
        environment.sleep(Duration.ofHours(24).plusSeconds(1));
        for (int sequence = 1; sequence <= 260; sequence++) {
            workflow.commandAccepted(command(sequence));
        }
        environment.getWorkerFactory().resumePolling();

        RoomControlSnapshot continued =
                awaitState(
                        workflow,
                        snapshot ->
                                snapshot.runGeneration() == 1
                                        && snapshot.processedCommandCount() == 260);
        assertThat(continued.pendingCommandCount()).isZero();

        workflow.commandAccepted(command(261));
        RoomControlSnapshot afterSentinel =
                awaitState(
                        workflow,
                        snapshot -> snapshot.processedCommandCount() == 261);
        assertThat(afterSentinel.recentCommandIds())
                .contains("command-room-control-261");
    }

    @Test
    void v2BindingSurvivesContinueAsNew() {
        String workflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        CASE_ID, RoomType.INTAKE, 0);
        RoomControlWorkflow workflow =
                client.newWorkflowStub(
                        RoomControlWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(
                                        CaseProcessWorkflowProtocol
                                                .ROOM_CONTROL_TASK_QUEUE)
                                .build());
        WorkflowClient.start(
                workflow::run,
                provisionedStart(
                        RoomType.INTAKE,
                        "room-epoch-selection.v2",
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        "IntakeRoomWorkflow",
                        "intake-room.synthetic.v1"));
        ProvisionRoomEpochReceipt before = workflow.provisioningReceipt();

        environment.sleep(Duration.ofHours(24));

        RoomControlSnapshot continued =
                awaitState(workflow, snapshot -> snapshot.runGeneration() == 1);
        assertThat(continued.selectionSchemaVersion()).isEqualTo("room-epoch-selection.v2");
        assertThat(workflow.provisioningReceipt()).isEqualTo(before);
        assertThat(before.roomWorkflowType()).isEqualTo("IntakeRoomWorkflow");
        assertThat(before.roomWorkflowBuildId()).isEqualTo("intake-room.synthetic.v1");
    }

    @Test
    void roomStartRejectsInconsistentSelectionBindings() {
        assertThatThrownBy(
                        () ->
                                provisionedStart(
                                        RoomType.INTAKE,
                                        "room-epoch-selection.v1",
                                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                        "IntakeRoomWorkflow",
                                        "intake-room.synthetic.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("v1 room start cannot contain a room Workflow binding");
        assertThatThrownBy(
                        () ->
                                provisionedStart(
                                        RoomType.INTAKE,
                                        "room-epoch-selection.v2",
                                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                        "IntakeRoomWorkflow",
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roomWorkflowBuildId is invalid");
        assertThatThrownBy(
                        () ->
                                provisionedStart(
                                        RoomType.EVIDENCE,
                                        "room-epoch-selection.v2",
                                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                        "IntakeRoomWorkflow",
                                        "intake-room.synthetic.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "non-LEGACY v2 room start requires the IntakeRoomWorkflow binding");
    }

    private static RoomControlStart provisionedStart(
            RoomType roomType,
            String selectionSchemaVersion,
            String caseWorkflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId) {
        return new RoomControlStart(
                "room-control-start.v1",
                TENANT,
                CASE_ID,
                "epoch-" + roomType.name().toLowerCase(),
                "room-" + roomType.name().toLowerCase(),
                roomType,
                0,
                CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID),
                CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, roomType, 0),
                1,
                1,
                1,
                0,
                0,
                "ACTIVE",
                roomType.name(),
                "ACTIVE",
                NOW.plusSeconds(3600),
                WriterMode.SHADOW,
                selectionSchemaVersion,
                "case-process-contract.v1",
                caseWorkflowType,
                "case-control.v1",
                roomWorkflowType,
                roomWorkflowBuildId,
                roomType.name().toLowerCase() + ".v2",
                "2.0.0",
                "intake-checkpoint.v2",
                "agent-stream.v2",
                0,
                0,
                null,
                null,
                NOW,
                "case-workflow-run-v1",
                "a".repeat(64));
    }

    private static RoomControlSnapshot awaitState(
            RoomControlWorkflow workflow, Predicate<RoomControlSnapshot> predicate) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RuntimeException lastFailure = null;
        RoomControlSnapshot lastSnapshot = null;
        while (System.nanoTime() < deadline) {
            try {
                RoomControlSnapshot snapshot = workflow.state();
                lastSnapshot = snapshot;
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError(
                "room state did not converge; last snapshot=" + lastSnapshot,
                lastFailure);
    }

    private static CaseCommandRef command() {
        return command(1);
    }

    private static CaseCommandRef command(int sequence) {
        char hashCharacter = Character.forDigit(sequence % 16, 16);
        return new CaseCommandRef(
                "case-command-ref.v1",
                "command-room-control-" + sequence,
                TENANT,
                CASE_ID,
                sequence,
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                3,
                new ActorRef(
                        "user-room-control", ActorRole.USER, List.of("case:command")),
                new PayloadRef(
                        "room-control-command.v1",
                        "urn:test:room-control:command:" + sequence,
                        String.valueOf(hashCharacter).repeat(64),
                        16),
                0,
                NOW.plusSeconds(1),
                NOW.plusSeconds(3600),
                "00-11111111111111111111111111111111-2222222222222222-01",
                String.valueOf(hashCharacter).repeat(64));
    }

    private static CaseDomainEventRef event() {
        return event(1);
    }

    private static CaseDomainEventRef event(int sequence) {
        char hashCharacter = Character.forDigit((sequence + 8) % 16, 16);
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                "event-room-control-" + sequence,
                TENANT,
                CASE_ID,
                sequence,
                "ROOM_CONTROL_EVENT",
                RoomType.EVIDENCE,
                3,
                new PayloadRef(
                        "room-control-event.v1",
                        "urn:test:room-control:event:" + sequence,
                        String.valueOf(hashCharacter).repeat(64),
                        16),
                NOW.plusSeconds(2),
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
    }
}
