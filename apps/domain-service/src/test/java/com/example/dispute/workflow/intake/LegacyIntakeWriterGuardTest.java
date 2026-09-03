package com.example.dispute.workflow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.intake.LegacyIntakeWriterGuard;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class LegacyIntakeWriterGuardTest {

    private static final String CASE_ID = "CASE_writer_guard";

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomEpochRepository epochRepository;
    @Mock private FulfillmentCaseEntity dispute;

    private LegacyIntakeWriterGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LegacyIntakeWriterGuard(caseRepository, epochRepository);
        lenient()
                .when(caseRepository.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(dispute));
    }

    @Test
    void locksTheCaseBeforeReadingTheWriterSlotAndAllowsLegacyCasesWithoutAnEpoch() {
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.empty());

        assertThatCode(() -> guard.assertLegacyWriteAllowed(CASE_ID))
                .doesNotThrowAnyException();

        InOrder locks = inOrder(caseRepository, epochRepository);
        locks.verify(caseRepository).findByIdForUpdate(CASE_ID);
        locks.verify(epochRepository).findWriterSlotByCaseIdForUpdate(CASE_ID);
    }

    @Test
    void requiresAnExistingTransactionForTheAuthorityLock() throws NoSuchMethodException {
        Transactional transactional =
                LegacyIntakeWriterGuard.class
                        .getMethod("assertLegacyWriteAllowed", String.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @ParameterizedTest
    @EnumSource(value = WriterMode.class, names = {"LEGACY", "SHADOW"})
    void allowsJavaFormalWriterModes(WriterMode writerMode) {
        CaseRoomEpochEntity lockedEpoch =
                epoch(RoomType.INTAKE, writerMode, EpochLifecycleStatus.ACTIVE);
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(lockedEpoch));

        assertThatCode(() -> guard.assertLegacyWriteAllowed(CASE_ID))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(
            value = EpochLifecycleStatus.class,
            names = {"PREPARING", "PROVISIONING", "ACTIVE"})
    void rejectsEveryTemporalIntakeWriterLifecycle(EpochLifecycleStatus lifecycle) {
        CaseRoomEpochEntity lockedEpoch =
                epoch(RoomType.INTAKE, WriterMode.TEMPORAL, lifecycle);
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(lockedEpoch));

        assertThatThrownBy(() -> guard.assertLegacyWriteAllowed(CASE_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo(ErrorCode.CASE_STATUS_INVALID);
                            assertThat(failure.details())
                                    .containsEntry(
                                            "reason_code",
                                            LegacyIntakeWriterGuard.REASON_CODE)
                                    .containsEntry("case_id", CASE_ID)
                                    .containsEntry("room_epoch", 7L)
                                    .containsEntry("writer_mode", "TEMPORAL")
                                    .containsEntry("lifecycle_status", lifecycle.name());
                        });
    }

    @Test
    void doesNotBlockAWorkflowOwnedEpochForAnotherRoom() {
        CaseRoomEpochEntity lockedEpoch =
                epoch(
                        RoomType.EVIDENCE,
                        WriterMode.TEMPORAL,
                        EpochLifecycleStatus.ACTIVE);
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(lockedEpoch));

        assertThatCode(() -> guard.assertLegacyWriteAllowed(CASE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void failsBeforeReadingWriterAuthorityWhenTheCaseCannotBeLocked() {
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.assertLegacyWriteAllowed(CASE_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CASE_NOT_FOUND));
        verifyNoInteractions(epochRepository);
    }

    private static CaseRoomEpochEntity epoch(
            RoomType roomType,
            WriterMode writerMode,
            EpochLifecycleStatus lifecycle) {
        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        lenient().when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomType()).thenReturn(roomType);
        lenient().when(epoch.getRoomEpoch()).thenReturn(7L);
        lenient().when(epoch.getWriterMode()).thenReturn(writerMode);
        lenient().when(epoch.getLifecycleStatus()).thenReturn(lifecycle);
        return epoch;
    }
}
