package com.example.dispute.workflow.targete2e.ingress.branch;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.ingress.IntakeIngressSelection;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationAuthority;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionReadiness;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeEpochBinding;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Routes only an active, ready Temporal Intake epoch to the target branch command adapter. */
@Service
public final class EpochAwareIntakeBranchIngressRouter {

    private final CaseRoomEpochRepository epochs;
    private final List<TargetIntakeActivationAuthority> authorities;
    private final List<TargetIntakeBranchIngress> delegates;
    private final TargetIntakeCommandAdmissionReadiness commandAdmissionReadiness;

    public EpochAwareIntakeBranchIngressRouter(
            CaseRoomEpochRepository epochs,
            List<TargetIntakeActivationAuthority> authorities,
            List<TargetIntakeBranchIngress> delegates,
            TargetIntakeCommandAdmissionReadiness commandAdmissionReadiness) {
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.authorities = List.copyOf(authorities);
        this.delegates = List.copyOf(delegates);
        this.commandAdmissionReadiness =
                Objects.requireNonNull(commandAdmissionReadiness, "commandAdmissionReadiness");
    }

    public IntakeIngressSelection select(String caseId) {
        CaseRoomEpochEntity epoch = epochs.findWriterSlotByCaseIdForUpdate(caseId).orElse(null);
        if (epoch == null
                || epoch.getRoomType() != RoomType.INTAKE
                || epoch.getWriterMode() != WriterMode.TEMPORAL) {
            return IntakeIngressSelection.legacy();
        }
        if (epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE
                || epoch.getProvisioningStatus() != EpochProvisioningStatus.READY) {
            throw invalidState("TARGET_E2E_INTAKE_EPOCH_NOT_READY", "target Intake epoch is not ready", epoch);
        }
        if (authorities.size() != 1 || delegates.size() != 1) {
            throw invalidState(
                    "TARGET_E2E_INTAKE_BRANCH_AUTHORITY_UNAVAILABLE",
                    "target Intake branch ingress requires exactly one authority and adapter",
                    epoch);
        }
        TargetIntakeEpochBinding binding =
                new TargetIntakeEpochBinding(
                        epoch.getTenantSurrogate(),
                        epoch.getCaseId(),
                        epoch.getRoomEpoch(),
                        epoch.getFencingToken(),
                        epoch.getProcessRevision(),
                        epoch.getRoomRevision(),
                        epoch.getTemporalWorkflowId(),
                        epoch.getTemporalBuildId());
        TargetIntakeActivationGrant grant = authorities.getFirst().authorize(binding);
        if (grant == null) {
            throw invalidState(
                    "TARGET_E2E_INTAKE_BRANCH_AUTHORITY_UNAVAILABLE",
                    "target Intake branch authority did not issue a grant",
                    epoch);
        }
        grant.assertMatches(binding);
        return IntakeIngressSelection.target(grant);
    }

    public TargetIntakeBranchIngressReceipt dispatchTarget(
            IntakeIngressSelection selection, TargetIntakeBranchRequest request) {
        if (selection == null || !selection.isTarget()) {
            throw new IllegalArgumentException("target branch dispatch requires a target selection");
        }
        if (request == null || request.activation() != selection.targetGrant()) {
            throw new IllegalArgumentException("target branch request is not bound to its selection");
        }
        if (delegates.size() != 1) {
            throw new IllegalStateException("target Intake branch command adapter is unavailable");
        }
        commandAdmissionReadiness.assertDispatchAllowed(
                selection.targetGrant(), request.command().commandId());
        return delegates.getFirst().accept(request);
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
