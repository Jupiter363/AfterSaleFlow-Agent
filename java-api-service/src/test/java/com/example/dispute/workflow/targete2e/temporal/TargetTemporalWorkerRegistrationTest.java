package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflowImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetTemporalWorkerRegistrationTest {

  @Test
  void acceptsAnExplicitTargetOnlyDispatcherAndUniqueTypedRoomImplementations() {
    var registration =
        new TargetTemporalWorkerRegistration.Registration(
            "target-e2e",
            "TARGET_E2E_CANDIDATE",
            "p9act.v1.0123456789abcdef0123456789abcdef",
            "p9-control-build",
            TargetCaseProcessWorkflow.class,
            List.of(
                EvidenceRoomWorkflowImpl.class,
                HearingRoomWorkflowImpl.class,
                OutcomeRoomWorkflowImpl.class),
            List.of(new Object()),
            List.of(new Object()));

    assertThat(registration.caseProcessWorkflowImplementation())
        .isEqualTo(TargetCaseProcessWorkflow.class);
    assertThat(registration.roomWorkflowImplementations()).hasSize(3);
  }

  @Test
  void rejectsAnAbstractTargetDispatcher() {
    assertThatThrownBy(
            () ->
                new TargetTemporalWorkerRegistration.Registration(
                    "target-e2e",
                    "TARGET_E2E_CANDIDATE",
                    "p9act.v1.0123456789abcdef0123456789abcdef",
                    "p9-control-build",
                    AbstractTargetCaseProcessWorkflow.class,
                    List.of(
                        EvidenceRoomWorkflowImpl.class,
                        HearingRoomWorkflowImpl.class,
                        OutcomeRoomWorkflowImpl.class),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target registration requires a concrete target-only CaseProcess dispatcher");
  }

  @Test
  void rejectsDuplicateRoomWorkflowImplementationTypes() {
    assertThatThrownBy(
            () ->
                new TargetTemporalWorkerRegistration.Registration(
                    "target-e2e",
                    "TARGET_E2E_CANDIDATE",
                    "p9act.v1.0123456789abcdef0123456789abcdef",
                    "p9-control-build",
                    TargetCaseProcessWorkflow.class,
                    List.of(
                        EvidenceRoomWorkflowImpl.class,
                        EvidenceRoomWorkflowImpl.class,
                        HearingRoomWorkflowImpl.class),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target room Workflow implementation types must be unique");
  }

  @Test
  void rejectsAnyThreeConcreteClassesThatAreNotTheFrozenRoomImplementations() {
    assertThatThrownBy(
            () ->
                new TargetTemporalWorkerRegistration.Registration(
                    "target-e2e",
                    "TARGET_E2E_CANDIDATE",
                    "p9act.v1.0123456789abcdef0123456789abcdef",
                    "p9-control-build",
                    TargetCaseProcessWorkflow.class,
                    List.of(
                        EvidenceRoomWorkflowImpl.class,
                        HearingRoomWorkflowImpl.class,
                        UnrelatedConcreteWorkflow.class),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "target registration requires the exact Evidence, Hearing, and Outcome Workflow implementations");
  }

  private static final class TargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessWorkflow {

    @Override
    protected TargetTypedRoomChildHandle startTargetTypedRoomChild(
        ProvisionRoomEpoch request, String provisioningHash) {
      return new NoopHandle();
    }

    @Override
    protected TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
        ActiveChildDescriptor descriptor) {
      return new NoopHandle();
    }

    private static final class NoopHandle implements TargetTypedRoomChildHandle {

      @Override
      public WorkflowExecution execution() {
        return WorkflowExecution.newBuilder()
            .setWorkflowId("target-child")
            .setRunId("run-1")
            .build();
      }

      @Override
      public TargetTypedRoomDispatchReceipt commandAccepted(CaseCommandRef command) {
        return new TargetTypedRoomDispatchReceipt(
            command.roomType(), command.roomEpoch(), 1, command.expectedProcessRevision(), 0);
      }

      @Override
      public TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event) {
        return new TargetTypedRoomDispatchReceipt(
            event.roomType(), event.roomEpoch(), 1, 0, 0);
      }

      @Override
      public String initiatorActorScopeHash() {
        return null;
      }

      @Override
      public String respondentActorScopeHash() {
        return null;
      }

      @Override
      public void close(String reason) {}
    }
  }

  private abstract static class AbstractTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessWorkflow {}

  private static final class UnrelatedConcreteWorkflow {}
}
