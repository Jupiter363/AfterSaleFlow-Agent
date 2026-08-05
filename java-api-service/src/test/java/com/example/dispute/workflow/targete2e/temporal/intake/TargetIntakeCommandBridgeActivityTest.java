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
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
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
  private static final String INITIATOR_THREAD_ID = "grt.v1." + HASH_A.substring(0, 32);
  private static final String RESPONDENT_THREAD_ID = "grt.v1." + HASH_B.substring(0, 32);
  private static final Instant DEADLINE = Instant.parse("2026-07-28T10:00:00Z");
  private static final PinnedVersions INITIATOR_BRANCH_PINS = branchPins("initiator");
  private static final PinnedVersions RESPONDENT_BRANCH_PINS = branchPins("respondent");

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
    assertThat(bound.party())
        .isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR);
    assertThat(bound.payloadRef()).isEqualTo("s3://target/payload.json");
  }

  @Test
  void merchantInitiatedMessagesMapMerchantToInitiatorAndUserToRespondent() {
    Fixture merchant = fixture(true, ActorRole.MERCHANT);
    Fixture user = fixture(true, ActorRole.USER);

    var merchantBound =
        merchant
            .activity()
            .bindCommand(
                new TargetIntakeCommandBridgeActivities.BindRequest(merchant.command(), 17, 3));
    var userBound =
        user.activity()
            .bindCommand(new TargetIntakeCommandBridgeActivities.BindRequest(user.command(), 17, 3));

    assertThat(merchantBound.party())
        .isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR);
    assertThat(userBound.party())
        .isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.RESPONDENT);
    assertThat(merchantBound.actorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash("case-1", "merchant-local", ActorRole.MERCHANT));
    assertThat(userBound.actorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash("case-1", "user-local", ActorRole.USER));
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
                "case-1", "user-local", ActorRole.USER))
        .isEqualTo(expected);
    assertThat(
            TargetIntakeActorScopes.hash(
                "case-1", "user-local", ActorRole.USER))
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

  @Test
  void bindsInitiatorRejectWithExactV5BranchAuthorityAndPrivatePins() {
    Fixture fixture = fixture();
    CaseCommandRef command = branchCommand(CommandType.INTAKE_CONFIRM, ActorRole.USER);
    TargetIntakeCommandBridgeActivity activity =
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(null),
            new ObjectMapper(),
            partySource(false),
            request ->
                new TargetIntakeBranchContextSource.ResolvedBranchContext(
                    INITIATOR_THREAD_ID,
                    "session-initiator",
                    BranchOperation.INITIATOR_REJECT,
                    INITIATOR_BRANCH_PINS));

    var bound =
        activity.bindCommand(new TargetIntakeCommandBridgeActivities.BindRequest(command, 17, 1));

    assertThat(bound.executionContext()).isNotNull();
    assertThat(bound.executionContext().schemaVersion()).isEqualTo("intake-command-execution-context.v5");
    assertThat(bound.executionContext().branchOperation()).isEqualTo(BranchOperation.INITIATOR_REJECT);
    assertThat(bound.executionContext().threadId()).isEqualTo(INITIATOR_THREAD_ID);
    assertThat(bound.executionContext().expectedProcessRevision()).isEqualTo(1);
    assertThat(bound.executionContext().expectedRoomRevision()).isEqualTo(1);
    assertThat(bound.executionContext().branchPinnedVersions())
        .usingRecursiveComparison()
        .isEqualTo(INITIATOR_BRANCH_PINS);
    assertThat(bound.operationKey()).contains("initiator.reject");
  }

  @Test
  void oldExecutionContextsDoNotSerializeTheNewBranchAuthorityFields() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    IntakeCommandExecutionContext v2 = fixture().context();
    IntakeCommandExecutionContext v3 =
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v3",
            INITIATOR_THREAD_ID,
            "session-initiator",
            DEADLINE.toEpochMilli(),
            new RetryBudget("intake-retry-budget.v1", 0, 3, 0),
            BranchOperation.INITIATOR_ACCEPT);
    IntakeCommandExecutionContext v4 =
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v4",
            INITIATOR_THREAD_ID,
            "session-initiator",
            DEADLINE.toEpochMilli(),
            new RetryBudget("intake-retry-budget.v1", 0, 3, 0),
            BranchOperation.INITIATOR_ACCEPT,
            null,
            1L,
            1L);

    assertThat(mapper.valueToTree(v2).has("expectedProcessRevision")).isFalse();
    assertThat(mapper.valueToTree(v2).has("expectedRoomRevision")).isFalse();
    assertThat(mapper.valueToTree(v3).has("expectedProcessRevision")).isFalse();
    assertThat(mapper.valueToTree(v3).has("expectedRoomRevision")).isFalse();
    assertThat(mapper.valueToTree(v3).has("targetAgentRun")).isTrue();
    assertThat(mapper.valueToTree(v4).has("branchPinnedVersions")).isFalse();
  }

  @Test
  void bindsRespondentConfirmationToTheRespondentRegisteredThread() {
    CaseCommandRef command = branchCommand(CommandType.INTAKE_CONFIRM, ActorRole.MERCHANT);
    TargetIntakeCommandBridgeActivity activity =
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(null),
            new ObjectMapper(),
            partySource(false),
            request -> {
              assertThat(request.party())
                  .isEqualTo(
                      com.example.dispute.workflow.temporal.room.intake.IntakeParty.RESPONDENT);
              assertThat(request.actorScopeHash())
                  .isEqualTo(
                      TargetIntakeActorScopes.hash(
                          "case-1", "merchant-local", ActorRole.MERCHANT));
              return new TargetIntakeBranchContextSource.ResolvedBranchContext(
                  RESPONDENT_THREAD_ID,
                  "session-respondent",
                  BranchOperation.RESPONDENT_CONFIRM,
                  RESPONDENT_BRANCH_PINS);
            });

    var bound =
        activity.bindCommand(new TargetIntakeCommandBridgeActivities.BindRequest(command, 17, 3));

    assertThat(bound.party()).isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.RESPONDENT);
    assertThat(bound.executionContext().threadId()).isEqualTo(RESPONDENT_THREAD_ID);
    assertThat(bound.executionContext().agentSessionId()).isEqualTo("session-respondent");
    assertThat(bound.executionContext().schemaVersion()).isEqualTo("intake-command-execution-context.v5");
    assertThat(bound.executionContext().branchPinnedVersions())
        .usingRecursiveComparison()
        .isEqualTo(RESPONDENT_BRANCH_PINS);
  }

  @Test
  void merchantInitiatedBranchesMapMerchantToInitiatorAndUserToRespondent() {
    TargetIntakeCommandBridgeActivity activity =
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(null),
            new ObjectMapper(),
            partySource(true),
            request ->
                request.party()
                        == com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR
                    ? new TargetIntakeBranchContextSource.ResolvedBranchContext(
                        INITIATOR_THREAD_ID,
                        "session-merchant-initiator",
                        BranchOperation.INITIATOR_REJECT,
                        INITIATOR_BRANCH_PINS)
                    : new TargetIntakeBranchContextSource.ResolvedBranchContext(
                        RESPONDENT_THREAD_ID,
                        "session-user-respondent",
                        BranchOperation.RESPONDENT_CONFIRM,
                        RESPONDENT_BRANCH_PINS));

    var merchant =
        activity.bindCommand(
            new TargetIntakeCommandBridgeActivities.BindRequest(
                branchCommand(CommandType.INTAKE_CONFIRM, ActorRole.MERCHANT), 17, 3));
    var user =
        activity.bindCommand(
            new TargetIntakeCommandBridgeActivities.BindRequest(
                branchCommand(CommandType.INTAKE_CONFIRM, ActorRole.USER), 17, 3));

    assertThat(merchant.party())
        .isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR);
    assertThat(merchant.executionContext().threadId()).isEqualTo(INITIATOR_THREAD_ID);
    assertThat(merchant.executionContext().schemaVersion()).isEqualTo("intake-command-execution-context.v5");
    assertThat(merchant.executionContext().branchPinnedVersions())
        .usingRecursiveComparison()
        .isEqualTo(INITIATOR_BRANCH_PINS);
    assertThat(user.party())
        .isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.RESPONDENT);
    assertThat(user.executionContext().threadId()).isEqualTo(RESPONDENT_THREAD_ID);
    assertThat(user.executionContext().schemaVersion()).isEqualTo("intake-command-execution-context.v5");
    assertThat(user.executionContext().branchPinnedVersions())
        .usingRecursiveComparison()
        .isEqualTo(RESPONDENT_BRANCH_PINS);
  }

  @Test
  void branchBindingFailsClosedWhenLegacySourceOmitsRegisteredPins() {
    CaseCommandRef command = branchCommand(CommandType.INTAKE_CONFIRM, ActorRole.USER);
    TargetIntakeCommandBridgeActivity activity =
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(null),
            new ObjectMapper(),
            partySource(false),
            request ->
                new TargetIntakeBranchContextSource.ResolvedBranchContext(
                    INITIATOR_THREAD_ID,
                    "session-initiator",
                    BranchOperation.INITIATOR_ACCEPT));

    assertThatThrownBy(
            () ->
                activity.bindCommand(
                    new TargetIntakeCommandBridgeActivities.BindRequest(command, 17, 1)))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType())
                  .isEqualTo(TargetIntakeCommandBridgeActivity.BINDING_INVALID);
              assertThat(failure.isNonRetryable()).isTrue();
            });
  }

  @Test
  void failsClosedWhenTheBranchSourceRejectsAnExactBindingMismatch() {
    CaseCommandRef command = branchCommand(CommandType.INTAKE_CANCEL, ActorRole.USER);
    TargetIntakeCommandBridgeActivity activity =
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(null),
            new ObjectMapper(),
            partySource(false),
            request -> {
              throw new IllegalArgumentException("registered private-thread binding mismatch");
            });

    assertThatThrownBy(
            () ->
                activity.bindCommand(
                    new TargetIntakeCommandBridgeActivities.BindRequest(command, 17, 3)))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure ->
                assertThat(failure.getType())
                    .isEqualTo(TargetIntakeCommandBridgeActivity.BINDING_INVALID));
  }

  private static CaseCommandRef branchCommand(CommandType type, ActorRole role) {
    String hash = type == CommandType.INTAKE_CANCEL ? HASH_D : HASH_E;
    return new CaseCommandRef(
        "case-command-ref.v1",
        "branch-command-1",
        "tenant-1",
        "case-1",
        5,
        type,
        RoomType.INTAKE,
        2,
        new ActorRef(
            role == ActorRole.USER ? "user-local" : "merchant-local",
            role,
            List.of("case:case-1:command:INTAKE_MESSAGE")),
        new PayloadRef(
            "intake-branch-command.v1",
            "minio://target-e2e-intake-activation/browser-messages/intake-branch-command.v1/branch-command-1/"
                + hash
                + ".json",
            hash,
            1),
        1,
        DEADLINE.minusSeconds(60),
        DEADLINE,
        "00-" + HASH_A.substring(0, 32) + "-" + HASH_B.substring(0, 16) + "-01",
        HASH_A);
  }

  private static PinnedVersions branchPins(String party) {
    return new PinnedVersions(
        "intake-pinned-versions.v2",
        "control-build-p9",
        "target-e2e-graph.2026-07-27.1",
        "target-e2e-checkpoint.v1",
        "intake-private-" + party + "-prompt.v1",
        "intake-private-" + party + "-model.v1",
        "target-e2e-room-proposal-source.v1",
        "intake-private-policy.v1",
        "intake-private-guardrail.v1",
        "tools.none.v1");
  }

  private static Fixture fixture() {
    return fixture(false, ActorRole.USER);
  }

  private static Fixture fixture(boolean merchantInitiated, ActorRole actorRole) {
    String actorId = actorRole == ActorRole.USER ? "user-local" : "merchant-local";
    RoomGraphCommand.ActorScope actorScope =
        TargetIntakeActorScopes.scope("case-1", actorId, actorRole);
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
                actorId,
                actorRole,
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
        new TargetIntakeCommandBridgeActivity(
            new FixedMaterialStore(material), new ObjectMapper(), partySource(merchantInitiated)),
        command,
        context,
        graph);
  }

  private static TargetIntakePartyScopeSource partySource(boolean merchantInitiated) {
    return request ->
        TargetIntakePartyScopeSource.ResolvedPartyScopes.create(
            "p9act.v1." + HASH_A.substring(0, 32),
            HASH_E,
            request,
            merchantInitiated ? "merchant-local" : "user-local",
            merchantInitiated ? ActorRole.MERCHANT : ActorRole.USER,
            merchantInitiated ? "user-local" : "merchant-local",
            merchantInitiated ? ActorRole.USER : ActorRole.MERCHANT);
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
