package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import io.temporal.testing.TestWorkflowEnvironment;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TargetTypedRoomCaseProcessDispatcherTest {

  @Test
  void ordinaryArtifactKeepsTheDispatcherAbstract() {
    assertThat(Modifier.isAbstract(TargetTypedRoomCaseProcessDispatcher.class.getModifiers()))
        .isTrue();
  }

  @Test
  void reviewUsesItsOwnHandleInsteadOfTheGenericCoordinateOnlyAdapter() {
    assertThat(
            Arrays.stream(TargetTypedRoomCaseProcessDispatcher.class.getDeclaredClasses())
                .map(Class::getSimpleName))
        .contains("ReviewHandle");
  }

  @Test
  void targetOnlyConcreteSubclassAndFrozenRoomTypesRegisterWithTemporal() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      var caseWorker = environment.newWorker("target-case-registration-test");
      var roomWorker = environment.newWorker("target-room-registration-test");

      assertThatCode(
              () ->
                  caseWorker.registerWorkflowImplementationTypes(
                      ConcreteTargetCaseProcessWorkflow.class))
          .doesNotThrowAnyException();
      assertThatCode(
              () ->
                  roomWorker.registerWorkflowImplementationTypes(
                      TargetTypedRoomProtocol.additionalWorkflowImplementations()
                          .toArray(Class[]::new)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void targetIntakeStartUsesTheExactActivationBoundProfilePinsDeterministically() {
    ProvisionRoomEpoch request = targetIntakeProvision();

    IntakeRoomStart first = TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request);
    IntakeRoomStart replay = TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request);

    assertThat(replay).isEqualTo(first);
    assertThat(first.workflowBuildId()).isEqualTo("control-build-p9");
    assertThat(first.graphVersion()).isEqualTo(TargetTypedRoomProtocol.GRAPH_VERSION);
    assertThat(first.checkpointSchemaVersion())
        .isEqualTo(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION);
    assertThat(first.promptVersion()).isEqualTo("all-rooms-prompt.target-e2e.v1");
    assertThat(first.modelProfileId()).isEqualTo("target-e2e.contract-blocked");
    assertThat(first.policyVersion()).isEqualTo("all-rooms-policy.target-e2e.v1");
    assertThat(first.guardrailVersion()).isEqualTo("all-rooms-guardrail.target-e2e.v1");
    assertThat(first.toolPolicyVersion()).isEqualTo("tools.none.v1");
    assertThat(first.initiatorActorScopeHash())
        .isNotEqualTo(first.respondentActorScopeHash());
  }

  private static ProvisionRoomEpoch targetIntakeProvision() {
    String tenant = "tenant-run001";
    String caseId = "QA_TARGET_INTAKE_1";
    long roomEpoch = 1;
    Instant requestedAt = Instant.parse("2026-07-30T01:00:00Z");
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-target-intake-1",
        tenant,
        caseId,
        "room-target-intake-1",
        RoomType.INTAKE,
        roomEpoch,
        4,
        2,
        11,
        "ACTIVE",
        "INTAKE",
        "ACTIVE",
        WriterMode.TEMPORAL,
        CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId),
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.INTAKE, roomEpoch),
        TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
        TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
        TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
        "case-build-p9",
        TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
        "control-build-p9",
        TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
        TargetTypedRoomProtocol.STREAM_PROTOCOL,
        5,
        7,
        6,
        8,
        requestedAt.plusSeconds(3_600),
        null,
        null,
        requestedAt);
  }

  public static final class ConcreteTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
