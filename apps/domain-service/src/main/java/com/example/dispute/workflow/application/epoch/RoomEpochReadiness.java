package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import java.util.Objects;

public final class RoomEpochReadiness {

    private RoomEpochReadiness() {}

    public static boolean isReady(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        if (!isEpochProvisioned(epoch)
                || projection == null
                || !sameTuple(epoch, projection)) {
            return false;
        }
        if (epoch.getWriterMode() == WriterMode.LEGACY) {
            return epoch.getProvisioningStatus() == EpochProvisioningStatus.NOT_REQUIRED
                    && projection.getWriterActivationStatus() == WriterActivationStatus.READY
                    && epoch.getTemporalWorkflowId() == null
                    && epoch.getTemporalRunId() == null
                    && projection.getTemporalWorkflowId() == null
                    && projection.getTemporalRunId() == null;
        }
        String expectedCaseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        epoch.getTenantSurrogate(), epoch.getCaseId());
        String expectedRoomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        epoch.getCaseId(), epoch.getRoomType(), epoch.getRoomEpoch());
        return epoch.getProvisioningStatus() == EpochProvisioningStatus.READY
                && projection.getWriterActivationStatus() == WriterActivationStatus.READY
                && expectedCaseWorkflowId.equals(epoch.getTemporalWorkflowId())
                && expectedCaseWorkflowId.equals(projection.getTemporalWorkflowId())
                && expectedRoomWorkflowId.equals(epoch.getRoomTemporalWorkflowId())
                && hasText(epoch.getTemporalRunId())
                && epoch.getTemporalRunId().equals(projection.getTemporalRunId())
                && hasText(epoch.getRoomTemporalRunId());
    }

    public static boolean isTemporalReady(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        return epoch != null
                && epoch.getWriterMode() != WriterMode.LEGACY
                && isReady(epoch, projection);
    }

    public static boolean isEpochProvisioned(CaseRoomEpochEntity epoch) {
        if (epoch == null || epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE) {
            return false;
        }
        if (epoch.getWriterMode() == WriterMode.LEGACY) {
            return epoch.getProvisioningStatus() == EpochProvisioningStatus.NOT_REQUIRED;
        }
        String expectedCaseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        epoch.getTenantSurrogate(), epoch.getCaseId());
        String expectedRoomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        epoch.getCaseId(), epoch.getRoomType(), epoch.getRoomEpoch());
        return epoch.getProvisioningStatus() == EpochProvisioningStatus.READY
                && expectedCaseWorkflowId.equals(epoch.getTemporalWorkflowId())
                && expectedRoomWorkflowId.equals(epoch.getRoomTemporalWorkflowId())
                && hasText(epoch.getTemporalRunId())
                && hasText(epoch.getRoomTemporalRunId());
    }

    private static boolean sameTuple(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        return epoch.getCaseId().equals(projection.getCaseId())
                && epoch.getTenantSurrogate().equals(projection.getTenantSurrogate())
                && epoch.getWriterMode() == projection.getWriterMode()
                && epoch.getRoomEpoch() == projection.getRoomEpoch()
                && epoch.getFencingToken() == projection.getFencingToken()
                && epoch.getProcessRevision() == projection.getProcessRevision()
                && Objects.equals(
                        epoch.getTemporalBuildId(), projection.getTemporalBuildId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
