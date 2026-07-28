package com.example.dispute.workflow.targete2e.temporal.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetIntakeCommandBridgeActivityTest {

  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);
  private static final String HASH_C = "c".repeat(64);
  private static final String HASH_D = "d".repeat(64);
  private static final String HASH_E = "e".repeat(64);
  private static final String HASH_F = "f".repeat(64);
  private static final Instant DEADLINE = Instant.parse("2026-07-28T10:00:00Z");

  @Test
  void returnsOnlyThePersistedV2ContextAfterExactAuthorityValidation() {
    Fixture fixture = fixture();

    var bound =
        fixture.activity().bindCommand(
            new TargetIntakeCommandBridgeActivities.BindRequest(fixture.command(), 17, 3));

    assertThat(bound.executionContext()).isSameAs(fixture.context());
    assertThat(fixture.graph().requestHash()).isNotEqualTo(fixture.command().requestHash());
    assertThat(bound.actorScopeHash())
        .isEqualTo(ContractJson.sha256Hex(new ObjectMapper().valueToTree(fixture.graph().actorScope())));
    assertThat(bound.payloadRef()).isEqualTo("s3://target/payload.json");
  }

  @Test
  void usesTheExactApiCompatibleLocalPartyScopeFormula() {
    RoomGraphCommand.ActorScope expected =
        new RoomGraphCommand.ActorScope(
            "user-local",
            ActorRole.USER,
            Audience.USER,
            List.of("case:case-1:command:INTAKE_MESSAGE"));

    assertThat(
            TargetIntakeActorScopes.scope(
                "case-1",
                com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR))
        .isEqualTo(expected);
    assertThat(
            TargetIntakeActorScopes.hash(
                "case-1",
                com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR))
        .isEqualTo(ContractJson.sha256Hex(new ObjectMapper().valueToTree(expected)));
  }

  @Test
  void failsClosedWhenTheCaseCommandDeadlineDiffersFromPersistedGraphMaterial() {
    Fixture fixture = fixture();
    CaseCommandRef changedDeadline =
        new CaseCommandRef(
            fixture.command().schemaVersion(),
            fixture.command().commandId(),
            fixture.command().tenantSurrogate(),
            fixture.command().caseId(),
            fixture.command().caseCommandSequence(),
            fixture.command().commandType(),
            fixture.command().roomType(),
            fixture.command().roomEpoch(),
            fixture.command().actorRef(),
            fixture.command().payloadRef(),
            fixture.command().expectedProcessRevision(),
            fixture.command().occurredAt(),
            fixture.command().deadlineAt().plusSeconds(1),
            fixture.command().traceparent(),
            fixture.command().requestHash());

    assertThatThrownBy(
            () ->
                fixture.activity().bindCommand(
                    new TargetIntakeCommandBridgeActivities.BindRequest(changedDeadline, 17, 3)))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure ->
                assertThat(failure.getType())
                    .isEqualTo(TargetIntakeCommandBridgeActivity.BINDING_INVALID));
  }

  private static Fixture fixture() {
    RoomGraphCommand.ActorScope actorScope =
        TargetIntakeActorScopes.scope(
            "case-1", com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR);
    RoomGraphCommand graph =
        new RoomGraphCommand(
            "room-graph-command.v1",
            "command-1",
            "run-1",
            "attempt-1",
            "tenant-1",
            "case-1",
            RoomType.INTAKE,
            2,
            "all-rooms.target-e2e.v1",
            "target-e2e-graph.2026-07-27.1",
            "target-e2e-checkpoint.v1",
            "grt.v1." + HASH_A.substring(0, 32),
            actorScope,
            9,
            "INTAKE",
            1,
            new RoomGraphCommand.SnapshotRef("snapshot-1", "snapshot.v1", "s3://target/snapshot", HASH_C, 1),
            new RoomGraphCommand.SnapshotRef("event-1", "payload.v1", "s3://target/payload.json", HASH_B, 2),
            new RoomGraphCommand.InvocationContext(
                "agent.v1", "prompt.v1", "model.v1", "output.v1", "policy.v1", "guard.v1", List.of(), "key-1", "nonce-1"),
            new RoomGraphCommand.RetryBudget(2, 3, 1),
            DEADLINE,
            "00-" + HASH_A.substring(0, 32) + "-" + HASH_B.substring(0, 16) + "-01",
            HASH_C);
    IntakeTargetAgentRunContext target =
        new IntakeTargetAgentRunContext(
            "intake-target-agent-run-context.v1",
            IntakeTargetAgentRunContext.TARGET_LANE,
            "p9act.v1." + HASH_A.substring(0, 32),
            HASH_E,
            17,
            9,
            3,
            "case-build", "control-build", "agent-build", HASH_D, "graph-build",
            HASH_A, HASH_F,
            new ExecuteAgentRunRequest(
                "execute-agent-run.v3", "run-1", 1, "agent-stream.v2", HASH_C, null, false, 0, graph));
    IntakeCommandExecutionContext context =
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v2",
            graph.threadId(),
            "session-1",
            DEADLINE.toEpochMilli(),
            new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
            null,
            target);
    CaseCommandRef command =
        new CaseCommandRef(
            "case-command-ref.v1",
            "command-1",
            "tenant-1",
            "case-1",
            4,
            CommandType.INTAKE_MESSAGE,
            RoomType.INTAKE,
            2,
            new ActorRef(
                "user-local",
                ActorRole.USER,
                List.of("case:case-1:command:INTAKE_MESSAGE")),
            new PayloadRef("payload.v1", "s3://target/payload.json", HASH_B, 2),
            9,
            DEADLINE.minusSeconds(60),
            DEADLINE,
            graph.traceparent(),
            HASH_A);
    CommandAdmission admission =
        new CommandAdmission(
            target.activationId(), HASH_E, HASH_D, "tenant-1", "case-1", "command-1", HASH_A, HASH_F, 2, 17);
    TargetIntakeCommandMaterialStore.MaterialSnapshot material =
        new TargetIntakeCommandMaterialStore.MaterialSnapshot("admission-1", admission, context, HASH_C, DEADLINE.minusSeconds(30));
    return new Fixture(
        new TargetIntakeCommandBridgeActivity(new FixedMaterialStore(material), new ObjectMapper()),
        command,
        context,
        graph);
  }

  private record Fixture(
      TargetIntakeCommandBridgeActivity activity,
      CaseCommandRef command,
      IntakeCommandExecutionContext context,
      RoomGraphCommand graph) {}

  private static final class FixedMaterialStore implements TargetIntakeCommandMaterialStore {
    private final MaterialSnapshot material;

    private FixedMaterialStore(MaterialSnapshot material) {
      this.material = material;
    }

    @Override
    public AppendResult append(CommandAdmission admission, IntakeCommandExecutionContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<MaterialSnapshot> read(CommandAdmission admission) {
      return Optional.of(material);
    }

    @Override
    public Optional<MaterialSnapshot> readByRoute(CommandLookup lookup) {
      return Optional.of(material);
    }
  }
}
