package com.example.dispute.workflow.temporal.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingPort.Binding;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeActorScopes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetE2ECaseProcessControlTest {

  @Test
  void targetIntakeFormalEventsRequirePrimaryProjectionCompletionOnlyOnV1() {
    ActiveChildDescriptor descriptor = intakeCompletionDescriptor();
    CaseDomainEventRef formal = intakeEvent("INTAKE_TURN_NEEDS_INPUT", 3);
    CaseDomainEventRef sourceOnly = intakeEvent("ROOM_MESSAGE_CREATED", 3);

    assertThat(
            CaseProcessWorkflowImpl.requiresTargetIntakeProjectionCompletion(
                1, descriptor, RoomType.INTAKE, 3, formal))
        .isTrue();
    assertThat(
            CaseProcessWorkflowImpl.requiresTargetIntakeProjectionCompletion(
                io.temporal.workflow.Workflow.DEFAULT_VERSION,
                descriptor,
                RoomType.INTAKE,
                3,
                formal))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.requiresTargetIntakeProjectionCompletion(
                1, descriptor, RoomType.INTAKE, 3, sourceOnly))
        .isFalse();
  }

  @Test
  void routesOnlyCanonicalGlobalProjectionReadyToTheCurrentTargetIntakeChild() {
    ActiveChildDescriptor intake = intakeCompletionDescriptor();
    CaseDomainEventRef readiness = globalEvent("INTAKE_PROJECTION_READY");

    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1, intake, RoomType.INTAKE, 3, readiness))
        .isTrue();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                io.temporal.workflow.Workflow.DEFAULT_VERSION,
                intake,
                RoomType.INTAKE,
                3,
                readiness))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                0, intake, RoomType.INTAKE, 3, readiness))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1, intake, RoomType.INTAKE, 3, globalEvent("CASE_STATUS_CHANGED")))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1, intake, RoomType.INTAKE, 3, globalEvent("INTAKE_TURN_NEEDS_INPUT")))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1, intake, RoomType.INTAKE, 3, intakeEvent("INTAKE_PROJECTION_READY", 3)))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1, intake, RoomType.INTAKE, 4, readiness))
        .isFalse();
    assertThat(
            CaseProcessWorkflowImpl.routesGlobalTargetIntakeProjectionReady(
                1,
                targetDescriptor(RoomType.EVIDENCE, null, evidenceBinding()),
                RoomType.EVIDENCE,
                3,
                readiness))
        .isFalse();
  }

  @Test
  void consumedIntakeProjectionReceiptMustEchoEveryAuthorityPin() {
    CompleteConsumedIntakeProjectionCommand command = completionCommand();
    CompleteConsumedIntakeProjectionResult matching = completionResult(command, command.eventId());
    CompleteConsumedIntakeProjectionResult wrongEvent = completionResult(command, "event.other");

    assertThat(
            CaseProcessWorkflowImpl.consumedIntakeProjectionResultMatches(command, matching))
        .isTrue();
    assertThat(
            CaseProcessWorkflowImpl.consumedIntakeProjectionResultMatches(command, wrongEvent))
        .isFalse();
  }

  @Test
  void targetMarkerSelectsOneFencedTypedChildKindForEveryRoomType() {
    for (RoomType roomType : RoomType.values()) {
      ActiveChildKind kind =
          CaseProcessWorkflowImpl.selectProvisionedChildKind(
              1,
              1,
              "room-epoch-selection.v2",
              TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
              WriterMode.TEMPORAL,
              roomType,
              "CaseProcessWorkflow",
              "p9-case-build",
              TargetTypedRoomProtocol.workflowType(roomType),
              "p9-control-build",
              "all-rooms.target-e2e.v1",
              TargetTypedRoomProtocol.GRAPH_VERSION,
              TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
              TargetTypedRoomProtocol.STREAM_PROTOCOL);

      assertThat(kind).isEqualTo(ActiveChildKind.TARGET_TYPED_ROOM);
    }
  }

  @Test
  void unknownHistoricalMarkerRejectsTemporalSelection() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    -1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.INTAKE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
                    "p9-control-build",
                    "all-rooms.target-e2e.v1",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_HISTORY_UNSUPPORTED");
  }

  @Test
  void targetDescriptorCarriesExactWorkflowBuildEpochAndFencePins() {
    for (RoomType roomType : RoomType.values()) {
      ActiveChildDescriptor descriptor =
          new ActiveChildDescriptor(
              ActiveChildKind.TARGET_TYPED_ROOM,
              "room-epoch-selection.v2",
              WriterMode.TEMPORAL,
              "CaseProcessWorkflow",
              "p9-case-build",
              TargetTypedRoomProtocol.workflowType(roomType),
              "p9-control-build",
              roomType,
              3,
              17,
              "room-child-3",
              "room-run-3",
              roomType == RoomType.INTAKE
                  ? TargetIntakeActorScopes.hash("case-3", "user-3", ActorRole.USER)
                  : null,
              roomType == RoomType.INTAKE
                  ? TargetIntakeActorScopes.hash("case-3", "merchant-3", ActorRole.MERCHANT)
                  : null,
               5L,
               11L,
               7L,
               13L,
               roomType == RoomType.REVIEW ? reviewBinding() : null,
               roomType == RoomType.EVIDENCE ? evidenceBinding() : null);

      CaseProcessWorkflowImpl.validateTargetTypedDescriptor(descriptor);
      assertThat(descriptor.roomType()).isEqualTo(roomType);
      assertThat(descriptor.roomEpoch()).isEqualTo(3);
      assertThat(descriptor.fencingToken()).isEqualTo(17);
      assertThat(descriptor.roomWorkflowBuildId()).isEqualTo("p9-control-build");
      assertThat(descriptor.initialProcessRevision()).isEqualTo(5);
      assertThat(descriptor.initialRoomRevision()).isEqualTo(11);
      assertThat(descriptor.currentProcessRevision()).isEqualTo(7);
      assertThat(descriptor.currentRoomRevision()).isEqualTo(13);
      if (roomType == RoomType.INTAKE) {
        assertThat(descriptor.initiatorActorScopeHash())
            .isEqualTo(TargetIntakeActorScopes.hash("case-3", "user-3", ActorRole.USER));
        assertThat(descriptor.withCurrentRevisions(8, 14).respondentActorScopeHash())
            .isEqualTo(
                TargetIntakeActorScopes.hash(
                    "case-3", "merchant-3", ActorRole.MERCHANT));
      }
    }
  }

  @Test
  void targetDescriptorPreservesMerchantInitiatedReplayStablePartyHashes() {
    String initiator =
        TargetIntakeActorScopes.hash("case-3", "merchant-3", ActorRole.MERCHANT);
    String respondent = TargetIntakeActorScopes.hash("case-3", "user-3", ActorRole.USER);
    ActiveChildDescriptor descriptor =
        new ActiveChildDescriptor(
            ActiveChildKind.TARGET_TYPED_ROOM,
            "room-epoch-selection.v2",
            WriterMode.TEMPORAL,
            "CaseProcessWorkflow",
            "p9-case-build",
            TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
            "p9-control-build",
            RoomType.INTAKE,
            3,
            17,
            "room-child-3",
            "room-run-3",
            initiator,
            respondent,
            5L,
            11L,
            7L,
            13L,
            null,
            null);

    CaseProcessWorkflowImpl.validateTargetTypedDescriptor(descriptor);
    assertThat(descriptor.withCurrentRevisions(8, 14).initiatorActorScopeHash())
        .isEqualTo(initiator);
    assertThat(descriptor.withCurrentRevisions(8, 14).respondentActorScopeHash())
        .isEqualTo(respondent);
  }

  @Test
  void targetReviewAndEvidenceDescriptorsRequireTheirReplayStableBindings() {
    assertThatThrownBy(
            () -> targetDescriptor(RoomType.REVIEW, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Outcome start binding");
    assertThatThrownBy(
            () -> targetDescriptor(RoomType.EVIDENCE, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participant binding");
  }

  private static ActiveChildDescriptor targetDescriptor(
      RoomType roomType,
      Binding reviewBinding,
      TargetEvidenceParticipantBindingActivities.Binding evidenceBinding) {
    return new ActiveChildDescriptor(
        ActiveChildKind.TARGET_TYPED_ROOM,
        "room-epoch-selection.v2",
        WriterMode.TEMPORAL,
        "CaseProcessWorkflow",
        "p9-case-build",
        TargetTypedRoomProtocol.workflowType(roomType),
        "p9-control-build",
        roomType,
        3,
        17,
        "room-child-3",
        "room-run-3",
        null,
        null,
        5L,
        11L,
        7L,
        13L,
        reviewBinding,
        evidenceBinding);
  }

  private static TargetEvidenceParticipantBindingActivities.Binding evidenceBinding() {
    return new TargetEvidenceParticipantBindingActivities.Binding(
        "tenant-3", "case-3", 3, 17, "user-3", "merchant-3", "a".repeat(64));
  }

  private static Binding reviewBinding() {
    return new Binding(
        "p9act.v1." + "a".repeat(32),
        "b".repeat(64),
        new OutcomeWorkflowStart(
            OutcomeWorkflowStart.SCHEMA_VERSION,
            "room-child-3",
            "case-3",
            "review-task-3",
            "review-packet-3",
            "c".repeat(64),
            "draft-3",
            "d".repeat(64),
            "action-3",
            "e".repeat(64),
            "operations-3",
            "f".repeat(64),
            0,
            3,
            11,
            17,
            Instant.parse("2026-07-28T00:00:00Z"),
            Instant.parse("2026-07-29T00:00:00Z"),
            OutcomeWireTypes.RuntimeMode.TEMPORAL,
            "p9-control-build",
            "policy-3",
            "graph-3",
            "prompt-3",
            "model-3",
            false));
  }

  private static ActiveChildDescriptor intakeCompletionDescriptor() {
    return new ActiveChildDescriptor(
        ActiveChildKind.TARGET_TYPED_ROOM,
        "room-epoch-selection.v2",
        WriterMode.TEMPORAL,
        "CaseProcessWorkflow",
        "p9-case-build",
        TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
        "p9-control-build",
        RoomType.INTAKE,
        3,
        17,
        "room-child-3",
        "room-run-3",
        TargetIntakeActorScopes.hash("case-3", "user-3", ActorRole.USER),
        TargetIntakeActorScopes.hash("case-3", "merchant-3", ActorRole.MERCHANT),
        0L,
        0L,
        1L,
        1L,
        null,
        null);
  }

  private static CaseDomainEventRef intakeEvent(String eventType, long roomEpoch) {
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "event.primary",
        "tenant-primary",
        "CASE_Primary",
        1,
        eventType,
        RoomType.INTAKE,
        roomEpoch,
        new PayloadRef("intake-event.v1", "urn:test:event:primary", "a".repeat(64), 16),
        Instant.parse("2026-08-02T12:00:00Z"),
        "00-11111111111111111111111111111111-2222222222222222-01");
  }

  private static CaseDomainEventRef globalEvent(String eventType) {
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "event.global." + eventType,
        "tenant-primary",
        "CASE_Primary",
        2,
        eventType,
        null,
        0,
        new PayloadRef("case-event.v1", "urn:test:event:global", "c".repeat(64), 16),
        Instant.parse("2026-08-02T12:00:01Z"),
        "00-11111111111111111111111111111111-2222222222222222-01");
  }

  private static CompleteConsumedIntakeProjectionCommand completionCommand() {
    return new CompleteConsumedIntakeProjectionCommand(
        "complete-consumed-intake-projection.v1",
        "tenant-primary",
        "CASE_Primary",
        "event.primary",
        1,
        "INTAKE_TURN_NEEDS_INPUT",
        1,
        3,
        17,
        1,
        1,
        "case-process:tenant-primary:CASE_Primary",
        "case-run-primary",
        "room-run-primary");
  }

  private static CompleteConsumedIntakeProjectionResult completionResult(
      CompleteConsumedIntakeProjectionCommand command, String eventId) {
    return new CompleteConsumedIntakeProjectionResult(
        "complete-consumed-intake-projection-result.v1",
        eventId,
        command.caseEventSequence(),
        CompleteConsumedIntakeProjectionOutcome.APPLIED,
        command.lastCommandSequence(),
        command.processRevision(),
        command.roomRevision(),
        command.roomEpoch(),
        command.fencingToken(),
        command.temporalWorkflowId(),
        command.firstExecutionRunId(),
        command.activeChildRunId(),
        "urn:test:intake:completion",
        "b".repeat(64),
        Instant.parse("2026-08-02T12:00:00Z"));
  }

  @Test
  void targetDescriptorRejectsMissingRevisionPins() {
    assertThatThrownBy(
            () ->
                new ActiveChildDescriptor(
                    ActiveChildKind.TARGET_TYPED_ROOM,
                    "room-epoch-selection.v2",
                    WriterMode.TEMPORAL,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.HEARING),
                    "p9-control-build",
                    RoomType.HEARING,
                    3,
                    17,
                    "room-child-3",
                    "room-run-3"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");
  }

  @Test
  void v1TemporalSelectionCannotBeRelabeledAsGenericRoomControl() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v1",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.EVIDENCE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    null,
                    null,
                    "evidence.v2",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }

  @Test
  void targetSelectionCannotBindAWorkflowForAnotherRoomType() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.EVIDENCE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.HEARING),
                    "p9-control-build",
                    "all-rooms.target-e2e.v1",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }

  @Test
  void targetSelectionCannotMixGraphProtocolPins() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.REVIEW,
                    TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.REVIEW),
                    "p9-control-build",
                    TargetTypedRoomProtocol.GRAPH_KEY,
                    "target-e2e-graph.mixed",
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }
}
