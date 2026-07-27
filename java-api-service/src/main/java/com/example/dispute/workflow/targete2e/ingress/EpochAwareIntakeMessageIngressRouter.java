package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.workflow.application.intake.LegacyIntakeWriterGuard;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EpochAwareIntakeMessageIngressRouter implements IntakeMessageIngressRouter {

    public static final String REASON_TARGET_AUTHORITY_UNAVAILABLE =
            "TARGET_E2E_INTAKE_AUTHORITY_UNAVAILABLE";
    public static final String REASON_TARGET_EPOCH_NOT_READY = "TARGET_E2E_INTAKE_EPOCH_NOT_READY";

    private final CaseRoomEpochRepository epochRepository;
    private final LegacyIntakeWriterGuard legacyWriterGuard;
    private final List<TargetIntakeActivationAuthority> activationAuthorities;
    private final List<TargetTemporalIntakeIngress> targetIngresses;

    public EpochAwareIntakeMessageIngressRouter(
            CaseRoomEpochRepository epochRepository,
            LegacyIntakeWriterGuard legacyWriterGuard,
            List<TargetIntakeActivationAuthority> activationAuthorities,
            List<TargetTemporalIntakeIngress> targetIngresses) {
        this.epochRepository = epochRepository;
        this.legacyWriterGuard = legacyWriterGuard;
        this.activationAuthorities = List.copyOf(activationAuthorities);
        this.targetIngresses = List.copyOf(targetIngresses);
    }

    @Override
    public IntakeIngressSelection select(String caseId) {
        CaseRoomEpochEntity epoch =
                epochRepository.findWriterSlotByCaseIdForUpdate(caseId).orElse(null);
        if (epoch == null
                || epoch.getRoomType() != RoomType.INTAKE
                || epoch.getWriterMode() != WriterMode.TEMPORAL) {
            legacyWriterGuard.assertLegacyWriteAllowed(caseId);
            return IntakeIngressSelection.legacy();
        }
        if (epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE
                || epoch.getProvisioningStatus() != EpochProvisioningStatus.READY) {
            throw invalidState(
                    REASON_TARGET_EPOCH_NOT_READY,
                    "target Intake epoch is not ready for browser commands",
                    epoch);
        }
        if (activationAuthorities.size() != 1 || targetIngresses.size() != 1) {
            throw invalidState(
                    REASON_TARGET_AUTHORITY_UNAVAILABLE,
                    "target Intake ingress requires exactly one authority and command adapter",
                    epoch);
        }
        TargetIntakeEpochBinding binding =
                new TargetIntakeEpochBinding(
                        epoch.getTenantSurrogate(),
                        epoch.getCaseId(),
                        epoch.getRoomEpoch(),
                        epoch.getFencingToken(),
                        epoch.getProcessRevision(),
                        epoch.getTemporalWorkflowId(),
                        epoch.getTemporalBuildId());
        TargetIntakeActivationGrant grant = activationAuthorities.getFirst().authorize(binding);
        if (grant == null) {
            throw invalidState(
                    REASON_TARGET_AUTHORITY_UNAVAILABLE,
                    "target Intake authority did not issue a grant",
                    epoch);
        }
        grant.assertMatches(binding);
        return IntakeIngressSelection.target(grant);
    }

    @Override
    public TargetIntakeIngressReceipt dispatchTarget(
            IntakeIngressSelection selection, TargetIntakeMessageRequest request) {
        if (selection == null || !selection.isTarget()) {
            throw new IllegalArgumentException("target dispatch requires a target selection");
        }
        if (request == null || request.activation() != selection.targetGrant()) {
            throw new IllegalArgumentException("target dispatch request is not bound to its selection");
        }
        if (targetIngresses.size() != 1) {
            throw new IllegalStateException("target Intake command adapter is unavailable");
        }
        return targetIngresses.getFirst().accept(request);
    }

    private static BusinessException invalidState(
            String reasonCode, String message, CaseRoomEpochEntity epoch) {
        return new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                message,
                Map.of(
                        "reason_code", reasonCode,
                        "case_id", epoch.getCaseId(),
                        "room_epoch", epoch.getRoomEpoch(),
                        "writer_mode", epoch.getWriterMode().name(),
                        "lifecycle_status", epoch.getLifecycleStatus().name(),
                        "provisioning_status", epoch.getProvisioningStatus().name()));
    }
}
