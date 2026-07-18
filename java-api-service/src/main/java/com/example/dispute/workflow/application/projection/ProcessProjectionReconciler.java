package com.example.dispute.workflow.application.projection;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.config.ProcessProjectionReconciliationProperties;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore.ClaimedRoomEpoch;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProcessProjectionReconciler {

    private static final int MAX_SCAN_SIZE = 1_000;
    private static final Logger log = LoggerFactory.getLogger(ProcessProjectionReconciler.class);

    private final AuthoritativeProcessStateReader authoritativeStateReader;
    private final ProcessProjectionReconciliationService reconciliationService;
    private final RoomEpochScanClaimStore scanClaimStore;
    private final ProcessProjectionReconciliationProperties properties;

    public ProcessProjectionReconciler(
            AuthoritativeProcessStateReader authoritativeStateReader,
            ProcessProjectionReconciliationService reconciliationService,
            RoomEpochScanClaimStore scanClaimStore,
            ProcessProjectionReconciliationProperties properties) {
        this.authoritativeStateReader = authoritativeStateReader;
        this.reconciliationService = reconciliationService;
        this.scanClaimStore = scanClaimStore;
        this.properties = properties;
    }

    public ProcessProjectionReconciliationResult reconcile(ReconciliationTarget target) {
        ReadResult authoritativeRead = authoritativeStateReader.read(target);
        return reconciliationService.reconcile(target, authoritativeRead);
    }

    public List<ProcessProjectionReconciliationResult> scan(int limit) {
        if (limit < 1 || limit > MAX_SCAN_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_SCAN_SIZE);
        }
        List<ClaimedRoomEpoch> candidates =
                scanClaimStore.claimProjectionReconciliation(
                        limit, properties.claimDuration());
        List<ProcessProjectionReconciliationResult> results = new ArrayList<>(candidates.size());
        for (ClaimedRoomEpoch candidate : candidates) {
            ReconciliationTarget target =
                    new ReconciliationTarget(
                            candidate.tenantSurrogate(),
                            candidate.caseId(),
                            candidate.temporalWorkflowId());
            try {
                requireClaimOwnership(candidate);
                ReadResult authoritativeRead = authoritativeStateReader.read(target);
                requireClaimOwnership(candidate);
                results.add(reconciliationService.reconcile(target, authoritativeRead));
            } catch (RuntimeException failure) {
                log.warn(
                        "Process projection reconciliation candidate failed: {}",
                        failure.getClass().getSimpleName());
            } finally {
                completeClaim(candidate);
            }
        }
        return List.copyOf(results);
    }

    private void completeClaim(ClaimedRoomEpoch candidate) {
        try {
            if (!scanClaimStore.completeProjectionReconciliation(
                    candidate, properties.pollInterval())) {
                log.warn("Projection reconciliation claim ownership was lost before completion");
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "Projection reconciliation claim completion failed: {}",
                    failure.getClass().getSimpleName());
        }
    }

    private void requireClaimOwnership(ClaimedRoomEpoch candidate) {
        if (!scanClaimStore.renewProjectionReconciliation(
                candidate, properties.claimDuration())) {
            throw new IllegalStateException(
                    "projection reconciliation claim or epoch ownership was lost");
        }
    }
}
