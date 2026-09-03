package com.example.dispute.workflow.projection;

import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.SOURCE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciler;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationService;
import com.example.dispute.workflow.config.ProcessProjectionReconciliationProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore.ClaimedRoomEpoch;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessProjectionReconcilerLeaseTest {

    private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);

    @Mock private AuthoritativeProcessStateReader authoritativeStateReader;
    @Mock private ProcessProjectionReconciliationService reconciliationService;
    @Mock private RoomEpochScanClaimStore scanClaimStore;

    @Test
    void completesPriorityBeforeClaimingTheFairLane() {
        ClaimedRoomEpoch first = candidate("first");
        ClaimedRoomEpoch second = candidate("second");
        ReconciliationTarget firstTarget = target(first);
        ReconciliationTarget secondTarget = target(second);
        ReadResult firstRead = unavailable();
        ReadResult secondRead = unavailable();
        ProcessProjectionReconciliationResult firstResult = result("FIRST_UNAVAILABLE");
        ProcessProjectionReconciliationResult secondResult = result("SECOND_UNAVAILABLE");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(first));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(second));
        when(scanClaimStore.renewProjectionReconciliation(first, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(second, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(first, POLL_INTERVAL))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(second, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(firstTarget)).thenReturn(firstRead);
        when(authoritativeStateReader.read(secondTarget)).thenReturn(secondRead);
        when(reconciliationService.reconcile(firstTarget, firstRead)).thenReturn(firstResult);
        when(reconciliationService.reconcile(secondTarget, secondRead)).thenReturn(secondResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(firstResult, secondResult);
        InOrder ordered =
                inOrder(scanClaimStore, authoritativeStateReader, reconciliationService);
        ordered.verify(scanClaimStore)
                .claimPriorityProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(first, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(firstTarget);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(first, CLAIM_DURATION);
        ordered.verify(reconciliationService).reconcile(firstTarget, firstRead);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(first, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(second, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(secondTarget);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(second, CLAIM_DURATION);
        ordered.verify(reconciliationService).reconcile(secondTarget, secondRead);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(second, POLL_INTERVAL);
    }

    @Test
    void completesAFailedPriorityCandidateBeforeClaimingFair() {
        ClaimedRoomEpoch failed = candidate("failed");
        ClaimedRoomEpoch succeeding = candidate("succeeding");
        ReconciliationTarget failedTarget = target(failed);
        ReconciliationTarget succeedingTarget = target(succeeding);
        ReadResult succeedingRead = unavailable();
        ProcessProjectionReconciliationResult succeedingResult = result("SUCCEEDING_UNAVAILABLE");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(failed));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(succeeding));
        when(scanClaimStore.renewProjectionReconciliation(failed, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(succeeding, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(failed, POLL_INTERVAL))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(succeeding, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(failedTarget))
                .thenThrow(new IllegalStateException("Temporal query failed"));
        when(authoritativeStateReader.read(succeedingTarget)).thenReturn(succeedingRead);
        when(reconciliationService.reconcile(succeedingTarget, succeedingRead))
                .thenReturn(succeedingResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(succeedingResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .claimPriorityProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(failed, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(failedTarget);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(failed, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(succeeding, CLAIM_DURATION);
    }

    @Test
    void targetConstructionFailureStillCompletesBeforeTheFairClaim() {
        ClaimedRoomEpoch invalid =
                new ClaimedRoomEpoch(
                        "claim-invalid",
                        "epoch-invalid",
                        " ",
                        "CASE_invalid",
                        RoomType.INTAKE,
                        1,
                        1,
                        "case-process:tenant-test:CASE_invalid");
        ClaimedRoomEpoch succeeding = candidate("after-invalid-target");
        ReconciliationTarget succeedingTarget = target(succeeding);
        ReadResult succeedingRead = unavailable();
        ProcessProjectionReconciliationResult succeedingResult = result("TARGET_RECOVERED");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(invalid));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(succeeding));
        when(scanClaimStore.completeProjectionReconciliation(invalid, POLL_INTERVAL))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(succeeding, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(succeeding, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(succeedingTarget)).thenReturn(succeedingRead);
        when(reconciliationService.reconcile(succeedingTarget, succeedingRead))
                .thenReturn(succeedingResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(succeedingResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .claimPriorityProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(invalid, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(succeeding, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(succeedingTarget);
    }

    @Test
    void lostRenewalCompletesTheClaimAndContinues() {
        ClaimedRoomEpoch lost = candidate("lost-renewal");
        ClaimedRoomEpoch succeeding = candidate("after-lost-renewal");
        ReconciliationTarget succeedingTarget = target(succeeding);
        ReadResult succeedingRead = unavailable();
        ProcessProjectionReconciliationResult succeedingResult = result("RENEWAL_RECOVERED");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(lost));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(succeeding));
        when(scanClaimStore.renewProjectionReconciliation(lost, CLAIM_DURATION))
                .thenReturn(false);
        when(scanClaimStore.completeProjectionReconciliation(lost, POLL_INTERVAL))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(succeeding, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(succeeding, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(succeedingTarget)).thenReturn(succeedingRead);
        when(reconciliationService.reconcile(succeedingTarget, succeedingRead))
                .thenReturn(succeedingResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(succeedingResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(lost, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(lost, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .renewProjectionReconciliation(succeeding, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(succeedingTarget);
    }

    @Test
    void completionOwnershipLossDoesNotStopTheNextClaim() {
        ClaimedRoomEpoch first = candidate("completion-lost");
        ClaimedRoomEpoch second = candidate("after-completion-lost");
        ReconciliationTarget firstTarget = target(first);
        ReconciliationTarget secondTarget = target(second);
        ReadResult firstRead = unavailable();
        ReadResult secondRead = unavailable();
        ProcessProjectionReconciliationResult firstResult = result("FIRST_COMPLETED");
        ProcessProjectionReconciliationResult secondResult = result("SECOND_COMPLETED");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(first));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(second));
        when(scanClaimStore.renewProjectionReconciliation(first, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(second, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(first, POLL_INTERVAL))
                .thenReturn(false);
        when(scanClaimStore.completeProjectionReconciliation(second, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(firstTarget)).thenReturn(firstRead);
        when(authoritativeStateReader.read(secondTarget)).thenReturn(secondRead);
        when(reconciliationService.reconcile(firstTarget, firstRead)).thenReturn(firstResult);
        when(reconciliationService.reconcile(secondTarget, secondRead)).thenReturn(secondResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(firstResult, secondResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(first, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(secondTarget);
    }

    @Test
    void completionExceptionDoesNotStopTheNextClaim() {
        ClaimedRoomEpoch first = candidate("completion-exception");
        ClaimedRoomEpoch second = candidate("after-completion-exception");
        ReconciliationTarget firstTarget = target(first);
        ReconciliationTarget secondTarget = target(second);
        ReadResult firstRead = unavailable();
        ReadResult secondRead = unavailable();
        ProcessProjectionReconciliationResult firstResult = result("FIRST_COMPLETED");
        ProcessProjectionReconciliationResult secondResult = result("SECOND_COMPLETED");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(first));
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(second));
        when(scanClaimStore.renewProjectionReconciliation(first, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.renewProjectionReconciliation(second, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(first, POLL_INTERVAL))
                .thenThrow(new IllegalStateException("completion unavailable"));
        when(scanClaimStore.completeProjectionReconciliation(second, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(firstTarget)).thenReturn(firstRead);
        when(authoritativeStateReader.read(secondTarget)).thenReturn(secondRead);
        when(reconciliationService.reconcile(firstTarget, firstRead)).thenReturn(firstResult);
        when(reconciliationService.reconcile(secondTarget, secondRead)).thenReturn(secondResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(firstResult, secondResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .completeProjectionReconciliation(first, POLL_INTERVAL);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(secondTarget);
    }

    @Test
    void emptyPriorityLaneFallsBackToTheFairLane() {
        ClaimedRoomEpoch fair = candidate("fair-fallback");
        ReconciliationTarget fairTarget = target(fair);
        ReadResult fairRead = unavailable();
        ProcessProjectionReconciliationResult fairResult = result("FAIR_FALLBACK");
        when(scanClaimStore.claimPriorityProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of());
        when(scanClaimStore.claimProjectionReconciliation(1, CLAIM_DURATION))
                .thenReturn(List.of(fair));
        when(scanClaimStore.renewProjectionReconciliation(fair, CLAIM_DURATION))
                .thenReturn(true);
        when(scanClaimStore.completeProjectionReconciliation(fair, POLL_INTERVAL))
                .thenReturn(true);
        when(authoritativeStateReader.read(fairTarget)).thenReturn(fairRead);
        when(reconciliationService.reconcile(fairTarget, fairRead)).thenReturn(fairResult);

        var results = reconciler().scan(2);

        assertThat(results).containsExactly(fairResult);
        InOrder ordered = inOrder(scanClaimStore, authoritativeStateReader);
        ordered.verify(scanClaimStore)
                .claimPriorityProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(scanClaimStore)
                .claimProjectionReconciliation(1, CLAIM_DURATION);
        ordered.verify(authoritativeStateReader).read(fairTarget);
    }

    private ProcessProjectionReconciler reconciler() {
        return new ProcessProjectionReconciler(
                authoritativeStateReader,
                reconciliationService,
                scanClaimStore,
                new ProcessProjectionReconciliationProperties(
                        true, 32, CLAIM_DURATION, POLL_INTERVAL));
    }

    private static ClaimedRoomEpoch candidate(String suffix) {
        return new ClaimedRoomEpoch(
                "claim-" + suffix,
                "epoch-" + suffix,
                "tenant-test",
                "CASE_" + suffix,
                RoomType.INTAKE,
                1,
                1,
                "case-process:tenant-test:CASE_" + suffix);
    }

    private static ReconciliationTarget target(ClaimedRoomEpoch candidate) {
        return new ReconciliationTarget(
                candidate.tenantSurrogate(),
                candidate.caseId(),
                candidate.temporalWorkflowId());
    }

    private static ReadResult unavailable() {
        return new Unavailable("TEMPORAL_QUERY_UNAVAILABLE");
    }

    private static ProcessProjectionReconciliationResult result(String reasonCode) {
        return new ProcessProjectionReconciliationResult(
                SOURCE_UNAVAILABLE, reasonCode, null, -1, -1);
    }
}
