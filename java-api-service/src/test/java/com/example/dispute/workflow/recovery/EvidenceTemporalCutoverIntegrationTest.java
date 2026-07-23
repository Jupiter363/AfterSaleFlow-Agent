package com.example.dispute.workflow.recovery;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.DecisionReason;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.SelectionDecision;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.SelectionRequest;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.TrafficAuthorization;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceTemporalCutoverIntegrationTest {

    private static final String CASE_ID = "CASE_P5_SYNTHETIC_SELECTOR";
    private static final long EPOCH = 15;
    private static final long FENCE = 71;

    @Test
    void signedSyntheticShadowKeepsOneJavaTimerAndEveryIneligibleSelectionFailsClosed() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            String taskQueue = "phase5-evidence-selector";
            String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":selector";
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
            WorkflowClient.start(workflow::run, start(openedAt));
            awaitTimerCount(environment.getWorkflowClient(), workflowId, 1);

            EvidenceEpochSelector selector = new EvidenceEpochSelector(RuntimeMode.SHADOW);
            SelectionDecision selected = selector.decide(request(
                    EvidenceEpochSelector.SELECTION_VERSION,
                    TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                    EPOCH,
                    FENCE,
                    1));

            assertThat(selected.runtimeMode()).isEqualTo(RuntimeMode.SHADOW);
            assertThat(selected.reason())
                    .isEqualTo(DecisionReason.AUTHENTICATED_SIGNED_SYNTHETIC_SHADOW);
            assertThat(selected.legacyTimerStartCount()).isZero();
            assertThat(selected.formalSinkReachable()).isFalse();

            List<SelectionDecision> rejected = List.of(
                    selector.decide(request(
                            "evidence-epoch-selection.unknown",
                            TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                            EPOCH,
                            FENCE,
                            1)),
                    selector.decide(request(
                            EvidenceEpochSelector.SELECTION_VERSION,
                            TrafficAuthorization.UNSIGNED_SYNTHETIC,
                            EPOCH,
                            FENCE,
                            1)),
                    selector.decide(request(
                            EvidenceEpochSelector.SELECTION_VERSION,
                            TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                            EPOCH - 1,
                            FENCE,
                            1)),
                    selector.decide(request(
                            EvidenceEpochSelector.SELECTION_VERSION,
                            TrafficAuthorization.JAVA_SIGNED_REAL_CASE,
                            EPOCH,
                            FENCE,
                            1)),
                    selector.decide(request(
                            EvidenceEpochSelector.SELECTION_VERSION,
                            TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                            EPOCH,
                            FENCE,
                            2)));

            assertThat(rejected)
                    .allSatisfy(decision -> {
                        assertThat(decision.runtimeMode()).isEqualTo(RuntimeMode.DISABLED);
                        assertThat(decision.legacyTimerStartCount()).isZero();
                        assertThat(decision.formalSinkReachable()).isFalse();
                    });
            assertThat(rejected)
                    .extracting(SelectionDecision::reason)
                    .containsExactly(
                            DecisionReason.UNKNOWN_SELECTION_VERSION,
                            DecisionReason.UNSIGNED_SYNTHETIC_FORBIDDEN,
                            DecisionReason.STALE_JAVA_AUTHORITY,
                            DecisionReason.REAL_CASE_FORBIDDEN,
                            DecisionReason.LEGACY_TIMER_INVARIANT_VIOLATION);
            assertThat(timerCount(environment.getWorkflowClient(), workflowId)).isOne();
        }
    }

    @Test
    void runtimeConfigurationCanOnlyBeDisabledOrShadow() {
        assertThat(new EvidenceEpochSelector(RuntimeMode.DISABLED).decide(null).runtimeMode())
                .isEqualTo(RuntimeMode.DISABLED);
        assertThatThrownBy(() -> new EvidenceEpochSelector(RuntimeMode.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED or signed synthetic SHADOW");
    }

    private static SelectionRequest request(
            String selectionVersion,
            TrafficAuthorization authorization,
            long selectedEpoch,
            long selectedFence,
            int activeLegacyTimerCount) {
        return new SelectionRequest(
                selectionVersion,
                RoomType.EVIDENCE,
                "TENANT_P5_SYNTHETIC_SELECTOR",
                CASE_ID,
                selectedEpoch,
                selectedFence,
                EPOCH,
                FENCE,
                activeLegacyTimerCount,
                authorization);
    }

    private static EvidenceRoomStart start(Instant openedAt) {
        return new EvidenceRoomStart(
                "evidence-room-start.v1",
                "TENANT_P5_SYNTHETIC_SELECTOR",
                CASE_ID,
                "ROOM_P5_EVIDENCE_SELECTOR",
                EPOCH,
                FENCE,
                "PARTICIPANT_P5_SELECTOR_INITIATOR",
                "PARTICIPANT_P5_SELECTOR_RESPONDENT",
                openedAt,
                openedAt.plus(Duration.ofHours(2)),
                1,
                3,
                5,
                "evidence-workflow.synthetic.v1");
    }

    private static void awaitTimerCount(WorkflowClient client, String workflowId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (timerCount(client, workflowId) >= expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError("expected " + expected + " Java timer start events");
    }

    private static long timerCount(WorkflowClient client, String workflowId) {
        return client.fetchHistory(workflowId).getEvents().stream()
                .filter(event -> event.getEventType() == EVENT_TYPE_TIMER_STARTED)
                .count();
    }
}
