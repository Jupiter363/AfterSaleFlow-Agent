package com.example.dispute.workflow.runtime.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RiskLevel;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.ingress.branch.EpochAwareIntakeBranchIngressRouter;
import com.example.dispute.workflow.runtime.ingress.branch.TargetIntakeBranchIngress;
import com.example.dispute.workflow.runtime.ingress.branch.TargetIntakeBranchIngressReceipt;
import com.example.dispute.workflow.runtime.ingress.branch.TargetIntakeBranchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EpochAwareIntakeBranchIngressRouterTest {

    private static final String CASE_ID = "CASE_TARGET_BRANCH";

    @Mock private CaseRoomEpochRepository epochs;
    @Mock private CaseCommandRepository commands;
    @Mock private TargetIntakeActivationAuthority authority;
    @Mock private TargetIntakeBranchIngress delegate;

    private EpochAwareIntakeBranchIngressRouter router;

    @BeforeEach
    void setUp() {
        router =
                new EpochAwareIntakeBranchIngressRouter(
                        epochs,
                        List.of(authority),
                        List.of(delegate),
                        new TargetIntakeCommandAdmissionReadiness(commands));
    }

    @Test
    void pendingProjectionRejectsANewBranchCommandBeforeTheDelegate() {
        TargetIntakeActivationGrant grant = grant();
        TargetIntakeBranchRequest request = request(grant, "intake-branch:NEW_1");
        when(commands.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        anyString(), anyLong(), anySet()))
                .thenReturn(true);
        when(commands.findByTenantSurrogateAndCommandId(
                        grant.tenantSurrogate(), request.command().commandId()))
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

        verify(commands)
                .findByTenantSurrogateAndCommandId(
                        grant.tenantSurrogate(), request.command().commandId());
        verifyNoInteractions(delegate);
    }

    @Test
    void pendingProjectionAllowsTheExactExistingBranchCommandToReachTheDelegate() {
        TargetIntakeActivationGrant grant = grant();
        TargetIntakeBranchRequest request = request(grant, "intake-branch:REPLAY_1");
        TargetIntakeBranchIngressReceipt expected =
                new TargetIntakeBranchIngressReceipt(
                        request.command().commandId(),
                        "a".repeat(64),
                        "PENDING_ORCHESTRATION",
                        true,
                        Instant.parse("2026-07-27T01:01:00Z"));
        when(commands.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        anyString(), anyLong(), anySet()))
                .thenReturn(true);
        when(commands.findByTenantSurrogateAndCommandId(
                        grant.tenantSurrogate(), request.command().commandId()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(CaseCommandEntity.class)));
        when(delegate.accept(request)).thenReturn(expected);

        TargetIntakeBranchIngressReceipt actual =
                router.dispatchTarget(IntakeIngressSelection.target(grant), request);

        assertThat(actual).isEqualTo(expected);
        verify(delegate).accept(request);
    }

    private static TargetIntakeBranchRequest request(
            TargetIntakeActivationGrant grant, String commandId) {
        return new TargetIntakeBranchRequest(
                CASE_ID,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeBranchCommand(
                        IntakeBranchCommand.SCHEMA_VERSION,
                        commandId,
                        CommandType.INTAKE_CONFIRM,
                        Party.INITIATOR,
                        IntakeBranchCommand.Operation.INITIATOR_ACCEPT,
                        true,
                        "ITEM_NOT_RECEIVED",
                        RiskLevel.LOW,
                        null,
                        null),
                "intake-branch-key",
                "TRACE_TARGET_INTAKE",
                Instant.parse("2026-07-27T01:00:00Z"),
                grant);
    }

    private static TargetIntakeActivationGrant grant() {
        return new TargetIntakeActivationGrant(
                TargetIntakeActivationGrant.TARGET_LANE,
                "p9act.v1." + "b".repeat(32),
                "a".repeat(64),
                "tenant-target",
                CASE_ID,
                7L,
                11L,
                13L,
                "case/tenant-target/" + CASE_ID,
                "target-control-build",
                Instant.parse("2026-07-27T02:00:00Z"));
    }
}
