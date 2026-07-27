package com.example.dispute.workflow.recovery;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.FailureBoundary;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.JavaReceiptObservation;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.JavaTruth;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.RollbackRequest;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.ShadowState;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.TrafficAuthorization;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSnapshot;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowRecoveryTest {

    private static final String CASE_ID = "CASE_P5_SYNTHETIC_RECOVERY";
    private static final long EPOCH = 31;
    private static final long FENCE = 401;
    private static final String INITIATOR = "PARTICIPANT_P5_RECOVERY_INITIATOR";
    private static final String RESPONDENT = "PARTICIPANT_P5_RECOVERY_RESPONDENT";

    @Test
    void timerRaceRollbackLeavesJavaHistoryAuthoritativeAndReplayable() throws Exception {
        WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            String taskQueue = "phase5-evidence-cutover-recovery";
            String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":recovery";
            environment.newWorker(taskQueue)
                    .registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
            environment.start();

            EvidenceRoomWorkflow workflow = environment.getWorkflowClient()
                    .newWorkflowStub(
                            EvidenceRoomWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue(taskQueue)
                                    .build());
            Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
            EvidenceRoomStart start = start(openedAt);
            WorkflowClient.start(workflow::run, start);
            awaitTimerCount(environment.getWorkflowClient(), workflowId, 1);

            workflow.partyCompleted(signal(INITIATOR, "COMPLETE_RECOVERY_INITIATOR", 3));
            EvidenceRoomSnapshot javaSnapshot = awaitInitiatorCompletion(workflow);
            JavaTruth javaTruth = javaTruth(start, javaSnapshot);
            long javaTimerStartsBeforeRollback =
                    timerCount(environment.getWorkflowClient(), workflowId);

            var rollbackOutcome = new EvidenceCutoverRollback().rollback(new RollbackRequest(
                    EvidenceCutoverRollback.ROLLBACK_REQUEST_VERSION,
                    FailureBoundary.TIMER_RACE,
                    EPOCH,
                    FENCE,
                    javaTruth,
                    new ShadowState(
                            RuntimeMode.SHADOW,
                            TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                            100,
                            0,
                            509),
                    JavaReceiptObservation.notQueried()));

            assertThat(rollbackOutcome.preservedJavaTruth()).isEqualTo(javaTruth);
            assertThat(rollbackOutcome.legacyTimerStartCount()).isZero();
            assertThat(javaTimerStartsBeforeRollback).isPositive();
            assertThat(timerCount(environment.getWorkflowClient(), workflowId))
                    .isEqualTo(javaTimerStartsBeforeRollback);

            workflow.partyCompleted(signal(RESPONDENT, "COMPLETE_RECOVERY_RESPONDENT", 4));
            EvidenceRoomSnapshot result =
                    WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
            assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
            assertThat(result.originalDeadlineAt()).isEqualTo(start.originalDeadlineAt());
            assertThat(result.deadlineExpired()).isFalse();
            assertThat(result.orderedOperationKeys())
                    .containsExactly(
                            EvidenceOperationKeys.partyComplete(
                                    CASE_ID, EPOCH, INITIATOR, "COMPLETE_RECOVERY_INITIATOR"),
                            EvidenceOperationKeys.partyComplete(
                                    CASE_ID, EPOCH, RESPONDENT, "COMPLETE_RECOVERY_RESPONDENT"));
            assertThat(timerCount(environment.getWorkflowClient(), workflowId))
                    .isEqualTo(javaTimerStartsBeforeRollback);
            history = environment.getWorkflowClient().fetchHistory(workflowId);
        }

        WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
    }

    private static JavaTruth javaTruth(EvidenceRoomStart start, EvidenceRoomSnapshot snapshot) {
        return new JavaTruth(
                snapshot.tenantSurrogate(),
                snapshot.caseId(),
                snapshot.roomEpoch(),
                snapshot.fencingToken(),
                snapshot.originalDeadlineAt(),
                EvidenceOperationKeys.deadlineWarn(CASE_ID, EPOCH, start.deadlineRevision()),
                1,
                snapshot.processRevision(),
                snapshot.roomRevision(),
                snapshot.warningSent(),
                snapshot.deadlineExpired(),
                Set.of("evidence://CASE_P5_SYNTHETIC_RECOVERY/submission/1"),
                null);
    }

    private static EvidenceRoomStart start(Instant openedAt) {
        return new EvidenceRoomStart(
                "evidence-room-start.v1",
                "TENANT_P5_SYNTHETIC_RECOVERY",
                CASE_ID,
                "ROOM_P5_EVIDENCE_RECOVERY",
                EPOCH,
                FENCE,
                INITIATOR,
                RESPONDENT,
                openedAt,
                openedAt.plus(Duration.ofHours(2)),
                1,
                5,
                7,
                "evidence-workflow.synthetic.v1");
    }

    private static EvidenceRoomSignal signal(
            String participantId, String completionRequestId, int digit) {
        return new EvidenceRoomSignal(
                "evidence-room-party-completion.v2",
                participantId,
                completionRequestId,
                EvidenceOperationKeys.partyComplete(
                        CASE_ID, EPOCH, participantId, completionRequestId),
                Integer.toString(digit).repeat(64),
                Instant.now());
    }

    private static EvidenceRoomSnapshot awaitInitiatorCompletion(EvidenceRoomWorkflow workflow) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        EvidenceRoomSnapshot last = null;
        while (System.nanoTime() < deadline) {
            last = workflow.state();
            if (last.initiatorCompleted()) {
                return last;
            }
            sleepBriefly();
        }
        throw new AssertionError("initiator completion did not converge: " + last);
    }

    private static void awaitTimerCount(WorkflowClient client, String workflowId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (timerCount(client, workflowId) >= expected) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("expected " + expected + " Java timer start events");
    }

    private static long timerCount(WorkflowClient client, String workflowId) {
        return client.fetchHistory(workflowId).getEvents().stream()
                .filter(event -> event.getEventType() == EVENT_TYPE_TIMER_STARTED)
                .count();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", exception);
        }
    }
}
