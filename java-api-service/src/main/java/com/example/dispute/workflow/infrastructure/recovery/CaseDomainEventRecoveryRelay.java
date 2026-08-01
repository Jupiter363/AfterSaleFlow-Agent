package com.example.dispute.workflow.infrastructure.recovery;

import com.example.dispute.workflow.config.CaseDomainEventRecoveryProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore.ClaimedRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CaseDomainEventRecoveryRelay {

    private static final Logger log = LoggerFactory.getLogger(CaseDomainEventRecoveryRelay.class);

    private final RoomEpochScanClaimStore scanClaimStore;
    private final CaseProcessLedgerActivities ledgerActivities;
    private final WorkflowClient workflowClient;
    private final CaseDomainEventRecoveryProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public CaseDomainEventRecoveryRelay(
            RoomEpochScanClaimStore scanClaimStore,
            CaseProcessLedgerActivities ledgerActivities,
            WorkflowClient workflowClient,
            CaseDomainEventRecoveryProperties properties) {
        this.scanClaimStore = scanClaimStore;
        this.ledgerActivities = ledgerActivities;
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    public RecoveryRun recoverAvailable() {
        if (!running.compareAndSet(false, true)) {
            return RecoveryRun.skipped();
        }
        try {
            int scannedWorkflows = 0;
            int recoveredWorkflows = 0;
            int deferredWorkflows = 0;
            int deliveredEvents = 0;
            int failedWorkflows = 0;
            Set<String> claimedEpochIds = new HashSet<>(2);
            for (int lane = 0; lane < 2; lane++) {
                List<ClaimedRoomEpoch> candidates =
                        lane == 0
                                ? scanClaimStore.claimPriorityDomainEventRecovery(
                                        1, properties.claimDuration())
                                : scanClaimStore.claimDomainEventRecovery(
                                        1, properties.claimDuration());
                if (candidates.isEmpty()) {
                    continue;
                }
                ClaimedRoomEpoch candidate = candidates.get(0);
                scannedWorkflows++;
                boolean failed = false;
                Duration nextScanDelay = properties.pollInterval();
                try {
                    if (!claimedEpochIds.add(candidate.epochId())) {
                        throw new IllegalStateException(
                                "scan lanes claimed the same room epoch twice");
                    }
                    RecoveryAttempt attempt = recover(candidate);
                    if (attempt.deferred()) {
                        deferredWorkflows++;
                    } else {
                        deliveredEvents += attempt.deliveredEvents();
                        if (attempt.deliveredEvents() > 0) {
                            recoveredWorkflows++;
                        }
                    }
                } catch (RuntimeException failure) {
                    failed = true;
                    nextScanDelay = properties.claimDuration();
                    log.warn(
                            "Case domain event recovery candidate failed: caseId={}, workflowId={}, failure={}: {}",
                            candidate.caseId(),
                            candidate.temporalWorkflowId(),
                            failure.getClass().getSimpleName(),
                            failure.getMessage(),
                            failure);
                } finally {
                    if (!completeClaim(candidate, nextScanDelay)) {
                        failed = true;
                    }
                }
                if (failed) {
                    failedWorkflows++;
                }
            }
            return RecoveryRun.completed(
                    scannedWorkflows,
                    recoveredWorkflows,
                    deferredWorkflows,
                    deliveredEvents,
                    failedWorkflows);
        } finally {
            running.set(false);
        }
    }

    private boolean completeClaim(ClaimedRoomEpoch candidate, Duration nextScanDelay) {
        try {
            boolean completed =
                    scanClaimStore.completeDomainEventRecovery(
                            candidate, nextScanDelay);
            if (!completed) {
                log.warn("Case domain event recovery claim ownership was lost before completion");
            }
            return completed;
        } catch (RuntimeException failure) {
            log.warn(
                    "Case domain event recovery claim completion failed: {}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private RecoveryAttempt recover(ClaimedRoomEpoch candidate) {
        requireClaimOwnership(candidate);
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        candidate.tenantSurrogate(), candidate.caseId());
        if (!expectedWorkflowId.equals(candidate.temporalWorkflowId())) {
            throw new IllegalStateException("active epoch workflow identity is invalid");
        }
        CaseProcessWorkflow workflow =
                workflowClient.newWorkflowStub(
                        CaseProcessWorkflow.class, candidate.temporalWorkflowId());
        requireClaimOwnership(candidate);
        CaseProcessSnapshot snapshot = workflow.state();
        if (!requireSnapshotScope(candidate, snapshot)) {
            return RecoveryAttempt.defer();
        }

        long fromSequence = snapshot.nextCaseEventSequence();
        long toSequence =
                Math.addExact(fromSequence, properties.eventBatchSize() - 1L);
        requireClaimOwnership(candidate);
        List<CaseDomainEventRef> events =
                ledgerActivities.loadDomainEvents(
                        new LoadSequenceRange(
                                "load-sequence-range.v1",
                                candidate.tenantSurrogate(),
                                candidate.caseId(),
                                fromSequence,
                                toSequence,
                                properties.eventBatchSize()));
        for (CaseDomainEventRef event : events) {
            if (!candidate.tenantSurrogate().equals(event.tenantSurrogate())
                    || !candidate.caseId().equals(event.caseId())) {
                throw new IllegalStateException("recovered event scope is invalid");
            }
            // Signals are intentionally at-least-once. The Workflow reduces duplicates by
            // event sequence and payload identity, including retries after an acknowledgement loss.
            requireClaimOwnership(candidate);
            workflow.domainEventCommitted(event);
        }
        return RecoveryAttempt.delivered(events.size());
    }

    private void requireClaimOwnership(ClaimedRoomEpoch candidate) {
        if (!scanClaimStore.renewDomainEventRecovery(
                candidate, properties.claimDuration())) {
            throw new IllegalStateException(
                    "case domain event recovery claim or epoch ownership was lost");
        }
    }

    private static boolean requireSnapshotScope(
            ClaimedRoomEpoch candidate, CaseProcessSnapshot snapshot) {
        if (snapshot == null
                || !candidate.temporalWorkflowId().equals(snapshot.workflowId())
                || snapshot.nextCaseEventSequence() < 1
                || snapshot.highestObservedEventSequence() < 0) {
            throw new IllegalStateException("Temporal case snapshot is invalid");
        }

        boolean unbound = snapshot.tenantSurrogate() == null && snapshot.caseId() == null;
        if (unbound) {
            if (snapshot.activeRoomType() != null
                    || snapshot.activeRoomEpoch() != -1
                    || snapshot.nextCaseEventSequence() != 1
                    || snapshot.highestObservedEventSequence() != 0) {
                throw new IllegalStateException("unbound Temporal case snapshot is inconsistent");
            }
            return false;
        }

        if (!candidate.tenantSurrogate().equals(snapshot.tenantSurrogate())
                || !candidate.caseId().equals(snapshot.caseId())
                || candidate.roomType() != snapshot.activeRoomType()
                || candidate.roomEpoch() != snapshot.activeRoomEpoch()) {
            throw new IllegalStateException("Temporal case snapshot scope or epoch is stale");
        }
        return true;
    }

    private record RecoveryAttempt(int deliveredEvents, boolean deferred) {

        static RecoveryAttempt delivered(int deliveredEvents) {
            return new RecoveryAttempt(deliveredEvents, false);
        }

        static RecoveryAttempt defer() {
            return new RecoveryAttempt(0, true);
        }
    }

    public record RecoveryRun(
            Status status,
            int scannedWorkflows,
            int recoveredWorkflows,
            int deferredWorkflows,
            int deliveredEvents,
            int failedWorkflows) {

        static RecoveryRun skipped() {
            return new RecoveryRun(Status.SKIPPED_ALREADY_RUNNING, 0, 0, 0, 0, 0);
        }

        static RecoveryRun completed(
                int scannedWorkflows,
                int recoveredWorkflows,
                int deferredWorkflows,
                int deliveredEvents,
                int failedWorkflows) {
            return new RecoveryRun(
                    Status.COMPLETED,
                    scannedWorkflows,
                    recoveredWorkflows,
                    deferredWorkflows,
                    deliveredEvents,
                    failedWorkflows);
        }
    }

    public enum Status {
        COMPLETED,
        SKIPPED_ALREADY_RUNNING
    }
}
