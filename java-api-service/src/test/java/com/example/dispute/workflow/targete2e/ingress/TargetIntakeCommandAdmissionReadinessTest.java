package com.example.dispute.workflow.targete2e.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TargetIntakeCommandAdmissionReadinessTest {

    @Mock private CaseCommandRepository commands;

    @Test
    void marksOnlyTheCurrentRevisionPendingForFormalReservationStatuses() {
        TargetIntakeCommandAdmissionReadiness readiness =
                new TargetIntakeCommandAdmissionReadiness(commands);
        when(commands.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        eq("CASE_TARGET_INGRESS"), eq(13L), anySet()))
                .thenReturn(true);
        when(commands.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        eq("CASE_TARGET_INGRESS"), eq(14L), anySet()))
                .thenReturn(false);

        assertThat(readiness.state("CASE_TARGET_INGRESS", 13L))
                .isEqualTo(TargetIntakeCommandAdmissionReadiness.CommandAdmissionState.PENDING);
        assertThat(readiness.state("CASE_TARGET_INGRESS", 14L))
                .isEqualTo(TargetIntakeCommandAdmissionReadiness.CommandAdmissionState.READY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<CommandStatus>> statuses = ArgumentCaptor.forClass(Set.class);
        verify(commands)
                .existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        eq("CASE_TARGET_INGRESS"), eq(13L), statuses.capture());
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(
                        CommandStatus.PENDING_ORCHESTRATION,
                        CommandStatus.ORCHESTRATION_ACCEPTED);
    }

}
