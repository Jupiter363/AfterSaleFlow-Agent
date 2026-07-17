package com.example.dispute.workflow.application.projection;

import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.SHADOW;
import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus.ACTIVE;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ProcessProjectionReconciler {

    private static final int MAX_SCAN_SIZE = 1_000;

    private final AuthoritativeProcessStateReader authoritativeStateReader;
    private final ProcessProjectionReconciliationService reconciliationService;
    private final CaseRoomEpochRepository roomEpochRepository;

    public ProcessProjectionReconciler(
            AuthoritativeProcessStateReader authoritativeStateReader,
            ProcessProjectionReconciliationService reconciliationService,
            CaseRoomEpochRepository roomEpochRepository) {
        this.authoritativeStateReader = authoritativeStateReader;
        this.reconciliationService = reconciliationService;
        this.roomEpochRepository = roomEpochRepository;
    }

    public ProcessProjectionReconciliationResult reconcile(ReconciliationTarget target) {
        ReadResult authoritativeRead = authoritativeStateReader.read(target);
        return reconciliationService.reconcile(target, authoritativeRead);
    }

    public List<ProcessProjectionReconciliationResult> scan(int limit) {
        if (limit < 1 || limit > MAX_SCAN_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_SCAN_SIZE);
        }
        List<CaseRoomEpochEntity> candidates =
                roomEpochRepository
                        .findByLifecycleStatusAndWriterModeInAndTemporalWorkflowIdIsNotNullOrderByUpdatedAtAsc(
                                ACTIVE,
                                EnumSet.of(SHADOW, TEMPORAL),
                                PageRequest.of(0, limit));
        LinkedHashMap<String, ReconciliationTarget> targets = new LinkedHashMap<>();
        for (CaseRoomEpochEntity candidate : candidates) {
            ReconciliationTarget target =
                    new ReconciliationTarget(
                            candidate.getTenantSurrogate(),
                            candidate.getCaseId(),
                            candidate.getTemporalWorkflowId());
            targets.putIfAbsent(candidate.getTemporalWorkflowId(), target);
        }
        return targets.values().stream().map(this::reconcile).toList();
    }
}
