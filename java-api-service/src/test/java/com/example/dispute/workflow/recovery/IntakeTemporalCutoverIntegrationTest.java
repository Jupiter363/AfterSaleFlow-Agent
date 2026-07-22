package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.signedAttempt;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class IntakeTemporalCutoverIntegrationTest {

    @Test
    void signedSyntheticMessageEndsOnlyInComparisonStorageAndReplaysIdempotently() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            IntakeSyntheticTestFixtures.Admission admission =
                    new IntakeSyntheticTestFixtures.Admission();
            IntakeSyntheticTestFixtures.InMemoryLedger ledger =
                    new IntakeSyntheticTestFixtures.InMemoryLedger();
            AtomicInteger snapshots = new AtomicInteger();
            AtomicInteger signedGraphExecutions = new AtomicInteger();
            AtomicInteger comparisons = new AtomicInteger();
            IntakeSyntheticWorkerRegistration registration =
                    new IntakeSyntheticWorkerRegistration(
                            admission,
                            request -> {
                                snapshots.incrementAndGet();
                                return IntakeSyntheticTestFixtures.snapshotReceipt(request);
                            },
                            request -> {
                                signedGraphExecutions.incrementAndGet();
                                return IntakeSyntheticTestFixtures.graphReceipt(request);
                            },
                            request -> {
                                comparisons.incrementAndGet();
                                return new Observation(
                                        IntakeSyntheticTestFixtures.paritySnapshot(),
                                        IntakeSyntheticTestFixtures.paritySnapshot(),
                                        IntakeDomainEventType.TURN_READY_TO_CONFIRM);
                            },
                            ledger);

            String workflowQueue = "phase4-intake-synthetic-cutover";
            environment.newWorker(workflowQueue)
                    .registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
            environment.newWorker(AGENT_EXECUTION)
                    .registerActivitiesImplementations(registration.activityImplementation());
            environment.start();

            IntakeRoomWorkflow workflow =
                    environment.getWorkflowClient()
                            .newWorkflowStub(
                                    IntakeRoomWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId("intake-room:synthetic-comparison:9")
                                            .setTaskQueue(workflowQueue)
                                            .build());
            WorkflowClient.start(workflow::run, IntakeSyntheticTestFixtures.start());
            var inert = IntakeSyntheticTestFixtures.inertCommand(
                    "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);

            var admitted = registration.driver().dispatch(
                    signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC),
                    inert,
                    workflow::commandAccepted);
            IntakeRoomSnapshot completed = awaitState(
                    workflow, snapshot -> snapshot.roomPhase() == IntakeRoomPhase.READY_TO_CONFIRM);

            assertThat(admitted.executionContext()).isNotNull();
            assertThat(completed.lastAgentRunRef()).isNotNull();
            assertThat(completed.lastGraphExecutionRef().graphKey()).isEqualTo("intake.v2");
            assertThat(completed.lastEventRef())
                    .startsWith("urn:after-sale-flow:intake-shadow-comparison:");
            assertThat(completed.processRevision()).isEqualTo(1);
            assertThat(snapshots).hasValue(1);
            assertThat(signedGraphExecutions).hasValue(1);
            assertThat(comparisons).hasValue(1);
            assertThat(ledger.writes).hasValue(1);

            registration.driver().dispatch(
                    signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC),
                    inert,
                    workflow::commandAccepted);
            environment.sleep(Duration.ofMillis(250));

            IntakeRoomSnapshot replayed = workflow.state();
            assertThat(replayed.processedCommandCount()).isEqualTo(1);
            assertThat(replayed.lastEventId()).isEqualTo(completed.lastEventId());
            assertThat(snapshots).hasValue(1);
            assertThat(signedGraphExecutions).hasValue(1);
            assertThat(comparisons).hasValue(1);
            assertThat(ledger.writes).hasValue(1);
        }
    }

    private static IntakeRoomSnapshot awaitState(
            IntakeRoomWorkflow workflow, Predicate<IntakeRoomSnapshot> predicate) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        IntakeRoomSnapshot last = null;
        while (System.nanoTime() < deadline) {
            last = workflow.state();
            if (predicate.test(last)) {
                return last;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError("synthetic Intake workflow did not converge: " + last);
    }
}
