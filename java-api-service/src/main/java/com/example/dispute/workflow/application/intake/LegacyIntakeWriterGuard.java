package com.example.dispute.workflow.application.intake;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes legacy Intake writes with room-epoch selection and rejects a Temporal-owned epoch. */
@Service
public class LegacyIntakeWriterGuard {

    public static final String REASON_CODE = "INTAKE_LEGACY_WRITER_REJECTED";

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomEpochRepository epochRepository;

    public LegacyIntakeWriterGuard(
            FulfillmentCaseRepository caseRepository,
            CaseRoomEpochRepository epochRepository) {
        this.caseRepository = caseRepository;
        this.epochRepository = epochRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertLegacyWriteAllowed(String caseId) {
        caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.CASE_NOT_FOUND,
                                        "case is unavailable for Intake writer validation",
                                        Map.of("case_id", caseId)));

        epochRepository
                .findWriterSlotByCaseIdForUpdate(caseId)
                .filter(LegacyIntakeWriterGuard::isTemporalIntakeWriter)
                .ifPresent(LegacyIntakeWriterGuard::rejectLegacyWrite);
    }

    private static boolean isTemporalIntakeWriter(CaseRoomEpochEntity epoch) {
        EpochLifecycleStatus lifecycle = epoch.getLifecycleStatus();
        return epoch.getRoomType() == RoomType.INTAKE
                && epoch.getWriterMode() == WriterMode.TEMPORAL
                && (lifecycle == EpochLifecycleStatus.PREPARING
                        || lifecycle == EpochLifecycleStatus.PROVISIONING
                        || lifecycle == EpochLifecycleStatus.ACTIVE);
    }

    private static void rejectLegacyWrite(CaseRoomEpochEntity epoch) {
        throw new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                "legacy Intake writes are disabled for a Temporal-owned room epoch",
                Map.of(
                        "reason_code", REASON_CODE,
                        "case_id", epoch.getCaseId(),
                        "room_epoch", epoch.getRoomEpoch(),
                        "writer_mode", epoch.getWriterMode().name(),
                        "lifecycle_status", epoch.getLifecycleStatus().name()));
    }
}
