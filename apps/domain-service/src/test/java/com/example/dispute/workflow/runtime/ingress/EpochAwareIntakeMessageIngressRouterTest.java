package com.example.dispute.workflow.runtime.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.workflow.application.intake.LegacyIntakeWriterGuard;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EpochAwareIntakeMessageIngressRouterTest {

    private static final String CASE_ID = "CASE_TARGET_INGRESS";
    private static final String HASH = "a".repeat(64);

    @Mock private CaseRoomEpochRepository epochRepository;
    @Mock private LegacyIntakeWriterGuard legacyWriterGuard;
    @Mock private CaseCommandRepository commandRepository;
    @Mock private TargetIntakeActivationAuthority activationAuthority;
    @Mock private TargetTemporalIntakeIngress targetIngress;
    @Mock private CaseRoomEpochEntity epoch;

    private EpochAwareIntakeMessageIngressRouter router;

    @BeforeEach
    void setUp() {
        router =
                new EpochAwareIntakeMessageIngressRouter(
                        epochRepository,
                        legacyWriterGuard,
                        List.of(activationAuthority),
                        List.of(targetIngress),
                        new TargetIntakeCommandAdmissionReadiness(commandRepository));
    }

    @Test
    void absentOrLegacyEpochPreservesTheLegacyGuardAndRoute() {
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.empty());

        IntakeIngressSelection selection = router.select(CASE_ID);

        assertThat(selection).isEqualTo(IntakeIngressSelection.legacy());
        verify(legacyWriterGuard).assertLegacyWriteAllowed(CASE_ID);
        verifyNoInteractions(activationAuthority, targetIngress);
    }

    @Test
    void temporalEpochWithoutExactlyOneTargetAuthorityFailsClosed() {
        configureReadyTemporalEpoch();
        router =
                new EpochAwareIntakeMessageIngressRouter(
                        epochRepository,
                        legacyWriterGuard,
                        List.of(),
                        List.of(targetIngress),
                        new TargetIntakeCommandAdmissionReadiness(commandRepository));

        assertThatThrownBy(() -> router.select(CASE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        failure ->
                                assertThat(((BusinessException) failure).details())
                                        .containsEntry(
                                                "reason_code",
                                                EpochAwareIntakeMessageIngressRouter
                                                        .REASON_TARGET_AUTHORITY_UNAVAILABLE));
        verify(legacyWriterGuard, never()).assertLegacyWriteAllowed(CASE_ID);
        verifyNoInteractions(targetIngress);
    }

    @Test
    void temporalEpochRequiresAnActiveReadyBinding() {
        configureReadyTemporalEpoch();
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.PROVISIONING);

        assertThatThrownBy(() -> router.select(CASE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        failure ->
                                assertThat(((BusinessException) failure).details())
                                        .containsEntry(
                                                "reason_code",
                                                EpochAwareIntakeMessageIngressRouter
                                                        .REASON_TARGET_EPOCH_NOT_READY));
        verify(legacyWriterGuard, never()).assertLegacyWriteAllowed(CASE_ID);
        verifyNoInteractions(activationAuthority, targetIngress);
    }

    @Test
    void authorizedTemporalEpochRoutesOnlyToTheTargetAdapter() {
        configureReadyTemporalEpoch();
        TargetIntakeActivationGrant grant = grant();
        when(activationAuthority.authorize(binding())).thenReturn(grant);
        TargetIntakeIngressReceipt expected =
                new TargetIntakeIngressReceipt(
                        "intake-message:MESSAGE_1",
                        "target-intake-run:MESSAGE_1",
                        HASH,
                        "PENDING_ORCHESTRATION",
                        false,
                        java.time.Instant.parse("2026-07-27T01:01:00Z"));
        TargetIntakeMessageRequest request = TestRequests.message(grant);
        when(targetIngress.accept(request)).thenReturn(expected);

        IntakeIngressSelection selection = router.select(CASE_ID);
        TargetIntakeIngressReceipt actual = router.dispatchTarget(selection, request);

        assertThat(actual).isEqualTo(expected);
        assertThat(selection.targetGrant()).isSameAs(grant);
        verify(legacyWriterGuard, never()).assertLegacyWriteAllowed(CASE_ID);
        verify(targetIngress).accept(request);
    }

    @Test
    void pendingProjectionRejectsADifferentFreshMessageBeforeTheDelegate() {
        TargetIntakeActivationGrant grant = grant();
        TargetIntakeMessageRequest request = TestRequests.message(grant);
        when(commandRepository.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        anyString(), anyLong(), anySet()))
                .thenReturn(true);
        when(commandRepository.findByTenantSurrogateAndCommandId(
                        grant.tenantSurrogate(),
                        TargetIntakeCommandIdentity.messageCommandId(grant, request)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                router.dispatchTarget(
                                        IntakeIngressSelection.target(grant), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        failure -> {
                            BusinessException rejection = (BusinessException) failure;
                            assertThat(rejection.errorCode()).isEqualTo(ErrorCode.CASE_STATUS_INVALID);
                            assertThat(rejection.details())
                                    .containsEntry(
                                            "reason_code",
                                            TargetIntakeCommandAdmissionReadiness
                                                    .REASON_PRODUCTION_RUNTIME_INTAKE_PROJECTION_PENDING);
                        });

        verifyNoInteractions(targetIngress);
    }

    @Test
    void pendingProjectionAllowsTheExactExistingMessageCommandToReachTheDelegate() {
        TargetIntakeActivationGrant grant = grant();
        TargetIntakeMessageRequest request = TestRequests.message(grant);
        String commandId = TargetIntakeCommandIdentity.messageCommandId(grant, request);
        TargetIntakeIngressReceipt expected =
                new TargetIntakeIngressReceipt(
                        commandId,
                        "target-intake-run:" + commandId.substring("intake-message:".length()),
                        HASH,
                        "PENDING_ORCHESTRATION",
                        true,
                        java.time.Instant.parse("2026-07-27T01:01:00Z"));
        when(commandRepository.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        anyString(), anyLong(), anySet()))
                .thenReturn(true);
        when(commandRepository.findByTenantSurrogateAndCommandId(grant.tenantSurrogate(), commandId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(CaseCommandEntity.class)));
        when(targetIngress.accept(request)).thenReturn(expected);

        TargetIntakeIngressReceipt actual =
                router.dispatchTarget(IntakeIngressSelection.target(grant), request);

        assertThat(actual).isEqualTo(expected);
        verify(targetIngress).accept(request);
    }

    private void configureReadyTemporalEpoch() {
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(epoch));
        lenient().when(epoch.getTenantSurrogate()).thenReturn("tenant-target");
        lenient().when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomType()).thenReturn(RoomType.INTAKE);
        lenient().when(epoch.getRoomEpoch()).thenReturn(7L);
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        lenient().when(epoch.getFencingToken()).thenReturn(11L);
        lenient().when(epoch.getProcessRevision()).thenReturn(13L);
        lenient()
                .when(epoch.getTemporalWorkflowId())
                .thenReturn("case/tenant-target/CASE_TARGET_INGRESS");
        lenient().when(epoch.getTemporalBuildId()).thenReturn("target-control-build");
    }

    private static TargetIntakeEpochBinding binding() {
        return new TargetIntakeEpochBinding(
                "tenant-target",
                CASE_ID,
                7L,
                11L,
                13L,
                "case/tenant-target/CASE_TARGET_INGRESS",
                "target-control-build");
    }

    private static TargetIntakeActivationGrant grant() {
        return new TargetIntakeActivationGrant(
                TargetIntakeActivationGrant.TARGET_LANE,
                "p9act.v1." + "b".repeat(32),
                HASH,
                "tenant-target",
                CASE_ID,
                7L,
                11L,
                13L,
                "case/tenant-target/CASE_TARGET_INGRESS",
                "target-control-build",
                java.time.Instant.parse("2026-07-27T02:00:00Z"));
    }
}
