package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.EVIDENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.config.CaseDomainEventRecoveryProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore.ClaimedRoomEpoch;
import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryRelay;
import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryRelay.Status;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseDomainEventRecoveryRelayTest {

    private static final String TENANT = "tenant-event-recovery";
    private static final String CASE_ID = "CASE_EventRecovery";
    private static final String WORKFLOW_ID =
            CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
    private static final ClaimedRoomEpoch CLAIM =
            new ClaimedRoomEpoch(
                    "claim-event-recovery",
                    "EPOCH_EventRecovery",
                    TENANT,
                    CASE_ID,
                    EVIDENCE,
                    2,
                    17,
                    WORKFLOW_ID);

    @Mock private RoomEpochScanClaimStore scanClaimStore;
    @Mock private CaseProcessLedgerActivities ledgerActivities;
    @Mock private WorkflowClient workflowClient;
    @Mock private CaseProcessWorkflow workflow;

    private CaseDomainEventRecoveryRelay relay;

    @BeforeEach
    void setUp() {
        relay =
                new CaseDomainEventRecoveryRelay(
                        scanClaimStore,
                        ledgerActivities,
                        workflowClient,
                        new CaseDomainEventRecoveryProperties(
                                true,
                                17,
                                23,
                                Duration.ofMinutes(5),
                                Duration.ofSeconds(5)));
        when(scanClaimStore.claimDomainEventRecovery(
                        17, Duration.ofMinutes(5)))
                .thenReturn(List.of(CLAIM));
        when(scanClaimStore.renewDomainEventRecovery(
                        CLAIM, Duration.ofMinutes(5)))
                .thenReturn(true);
        when(scanClaimStore.completeDomainEventRecovery(
                        CLAIM, Duration.ofSeconds(5)))
                .thenReturn(true);
        lenient().when(workflowClient.newWorkflowStub(CaseProcessWorkflow.class, WORKFLOW_ID))
                .thenReturn(workflow);
    }

    @Test
    void doesNotAdvanceAnUnboundWorkflowBeforeItsRoomChildExists() {
        when(workflow.state()).thenReturn(unboundSnapshot());

        var result = relay.recoverAvailable();

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.scannedWorkflows()).isEqualTo(1);
        assertThat(result.recoveredWorkflows()).isZero();
        assertThat(result.deferredWorkflows()).isEqualTo(1);
        assertThat(result.deliveredEvents()).isZero();
        assertThat(result.failedWorkflows()).isZero();
        verify(ledgerActivities, never()).loadDomainEvents(any());
        verify(workflow, never()).domainEventCommitted(any());
        verify(scanClaimStore)
                .completeDomainEventRecovery(CLAIM, Duration.ofSeconds(5));
    }

    @Test
    void resumesAfterTheLastContiguousSequenceSoAPreviouslyBufferedGapCanHeal() {
        CaseDomainEventRef sixth = event(6);
        when(workflow.state()).thenReturn(boundSnapshot(2, 6, 8));
        when(ledgerActivities.loadDomainEvents(range(6, 28)))
                .thenReturn(List.of(sixth));

        var result = relay.recoverAvailable();

        assertThat(result.deliveredEvents()).isEqualTo(1);
        verify(workflow).domainEventCommitted(sixth);
    }

    @Test
    void retryUsesTheSameDurableEnvelopeAndReliesOnWorkflowSequenceIdempotency() {
        CaseDomainEventRef first = event(1);
        when(workflow.state()).thenReturn(boundSnapshot(2, 1, 0));
        when(ledgerActivities.loadDomainEvents(range(1, 23)))
                .thenReturn(List.of(first));

        relay.recoverAvailable();
        relay.recoverAvailable();

        verify(workflow, times(2)).domainEventCommitted(first);
    }

    @Test
    void rejectsAStaleEpochBindingWithoutReadingOrSignallingTheLedger() {
        when(workflow.state()).thenReturn(boundSnapshot(3, 1, 0));

        var result = relay.recoverAvailable();

        assertThat(result.failedWorkflows()).isEqualTo(1);
        assertThat(result.deliveredEvents()).isZero();
        verify(ledgerActivities, never()).loadDomainEvents(any());
        verify(workflow, never()).domainEventCommitted(any());
    }

    @Test
    void lostJavaEpochOwnershipPreventsTemporalCalls() {
        when(scanClaimStore.renewDomainEventRecovery(
                        CLAIM, Duration.ofMinutes(5)))
                .thenReturn(false);

        var result = relay.recoverAvailable();

        assertThat(result.failedWorkflows()).isEqualTo(1);
        assertThat(result.deliveredEvents()).isZero();
        verify(workflowClient, never())
                .newWorkflowStub(CaseProcessWorkflow.class, WORKFLOW_ID);
        verify(ledgerActivities, never()).loadDomainEvents(any());
        verify(workflow, never()).domainEventCommitted(any());
    }

    private static LoadSequenceRange range(long from, long to) {
        return argThat(
                request ->
                        request != null
                                && TENANT.equals(request.tenantSurrogate())
                                && CASE_ID.equals(request.caseId())
                                && request.fromSequenceInclusive() == from
                                && request.toSequenceInclusive() == to
                                && request.limit() == 23);
    }

    private static CaseProcessSnapshot unboundSnapshot() {
        return snapshot(null, null, null, -1, 1, 0);
    }

    private static CaseProcessSnapshot boundSnapshot(
            long roomEpoch, long nextEventSequence, long highestObservedSequence) {
        return snapshot(
                TENANT,
                CASE_ID,
                EVIDENCE,
                roomEpoch,
                nextEventSequence,
                highestObservedSequence);
    }

    private static CaseProcessSnapshot snapshot(
            String tenant,
            String caseId,
            com.example.dispute.workflow.contract.v1.ContractTypes.RoomType roomType,
            long roomEpoch,
            long nextEventSequence,
            long highestObservedSequence) {
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                WORKFLOW_ID,
                "run-event-recovery-1",
                tenant,
                caseId,
                "CONTROL_PLANE_SHADOW",
                roomType,
                roomEpoch,
                roomType == null ? null : "room-workflow:event-recovery",
                0,
                1,
                nextEventSequence,
                0,
                Math.max(0, nextEventSequence - 1),
                0,
                0,
                0,
                0,
                highestObservedSequence,
                0,
                "NONE",
                null,
                List.of());
    }

    private static CaseDomainEventRef event(long sequence) {
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                "EVENT_RECOVERY_" + sequence,
                TENANT,
                CASE_ID,
                sequence,
                "ROOM_MESSAGE_CREATED",
                EVIDENCE,
                2,
                new PayloadRef(
                        "case-timeline-event.v1",
                        "urn:test:event-recovery:" + sequence,
                        "a".repeat(64),
                        2),
                Instant.parse("2026-07-18T08:00:00Z"),
                "00-" + "b".repeat(32) + "-" + "c".repeat(16) + "-01");
    }
}
