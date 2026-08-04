package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.ProcessProjectionActivities;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeActorScopes;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
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

    var partyScopes = partyScopes(request, ActorRole.USER);
    IntakeRoomStart first =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request, partyScopes);
    IntakeRoomStart replay =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request, partyScopes);

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
    assertThat(first.targetE2eCandidate()).isTrue();
    assertThat(first.initiatorActorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash(request.caseId(), "user-local", ActorRole.USER));
    assertThat(first.respondentActorScopeHash())
        .isEqualTo(
            TargetIntakeActorScopes.hash(request.caseId(), "merchant-local", ActorRole.MERCHANT));
  }

  @Test
  void legacyTargetIntakeStartKeepsThePreAuthorityDeterministicFallback() {
    ProvisionRoomEpoch request = targetIntakeProvision();

    IntakeRoomStart first =
        TargetTypedRoomCaseProcessDispatcher.legacyTargetIntakeStart(request);
    IntakeRoomStart replay =
        TargetTypedRoomCaseProcessDispatcher.legacyTargetIntakeStart(request);

    assertThat(replay).isEqualTo(first);
    assertThat(first.targetE2eCandidate()).isTrue();
    assertThat(first.initiatorActorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash(request.caseId(), "user-local", ActorRole.USER));
    assertThat(first.respondentActorScopeHash())
        .isEqualTo(
            TargetIntakeActorScopes.hash(
                request.caseId(), "merchant-local", ActorRole.MERCHANT));
  }

  @Test
  void targetIntakeStartReplaysMerchantInitiatedPartyScopesWithoutRoleInversion() {
    ProvisionRoomEpoch request = targetIntakeProvision();
    var partyScopes = partyScopes(request, ActorRole.MERCHANT);

    IntakeRoomStart first =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request, partyScopes);
    IntakeRoomStart replay =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeStart(request, partyScopes);

    assertThat(replay).isEqualTo(first);
    assertThat(first.initiatorActorScopeHash())
        .isEqualTo(
            TargetIntakeActorScopes.hash(
                request.caseId(), "merchant-local", ActorRole.MERCHANT));
    assertThat(first.respondentActorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash(request.caseId(), "user-local", ActorRole.USER));
  }

  @Test
  void targetEvidenceStartUsesExplicitTargetLaneWithRealLocalBuildId() {
    ProvisionRoomEpoch request = targetEvidenceProvision();
    var participants =
        new TargetEvidenceParticipantBindingActivities.Binding(
            request.tenantSurrogate(),
            request.caseId(),
            request.roomEpoch(),
            request.fencingToken(),
            "user-local",
            "merchant-local",
            "c".repeat(64));

    EvidenceRoomStart start =
        TargetTypedRoomCaseProcessDispatcher.targetEvidenceStart(request, participants);
    EvidenceRoomStart replay =
        TargetTypedRoomCaseProcessDispatcher.targetEvidenceStart(request, participants);

    assertThat(replay).isEqualTo(start);
    assertThat(start.workflowBuildId()).isEqualTo("local-d96956b7-control");
    assertThat(start.executionLane()).isEqualTo(ExecutionLane.TARGET_E2E_CANDIDATE);
    assertThat(start.targetE2eCandidate()).isTrue();
  }

  @Test
  void targetIntakeForwardsEveryNonFormalTimelineEventIntoTheChildCursor() {
    CaseDomainEventRef source = caseEvent(1, "ROOM_MESSAGE_CREATED");
    CaseDomainEventRef readiness = caseEvent(2, "INTAKE_PROJECTION_READY");

    TargetIntakeSourceEventRef observation =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorObservation(source, 11);

    assertThat(observation.eventId()).isEqualTo(source.eventId());
    assertThat(observation.eventSequence()).isEqualTo(1);
    assertThat(observation.eventType()).isEqualTo("ROOM_MESSAGE_CREATED");
    assertThat(observation.tenantSurrogate()).isEqualTo(source.tenantSurrogate());
    assertThat(observation.caseId()).isEqualTo(source.caseId());
    assertThat(observation.roomEpoch()).isEqualTo(source.roomEpoch());
    assertThat(observation.fencingToken()).isEqualTo(11);
    assertThat(observation.payloadHash()).isEqualTo(source.payloadRef().sha256());
    TargetIntakeSourceEventRef readinessObservation =
        TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorObservation(readiness, 11);
    assertThat(readinessObservation.eventId()).isEqualTo(readiness.eventId());
    assertThat(readinessObservation.eventSequence()).isEqualTo(2);
    assertThat(readinessObservation.eventType()).isEqualTo("INTAKE_PROJECTION_READY");
    assertThat(TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorChangeId(observation))
        .isEqualTo(
            TargetTypedRoomCaseProcessDispatcher.TARGET_INTAKE_SOURCE_EVENT_CURSOR_CHANGE_ID);
    assertThat(
            TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorChangeId(
                readinessObservation))
        .isEqualTo(
            TargetTypedRoomCaseProcessDispatcher
                .TARGET_INTAKE_COMPLETE_TIMELINE_CURSOR_CHANGE_ID);
    for (IntakeDomainEventType formalType : IntakeDomainEventType.values()) {
      assertThat(
              TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorObservation(
                  caseEvent(3 + formalType.ordinal(), formalType.name()), 11))
          .isNull();
    }
    String[] formalAliases = {
      "INTAKE_TURN_NEEDS_INPUT",
      "INTAKE_TURN_READY_TO_CONFIRM",
      "INITIATOR_INTAKE_COMPLETED",
      "INTAKE_REJECTED",
      "INTAKE_CANCELLED",
      "RESPONDENT_INTAKE_COMPLETED"
    };
    for (int index = 0; index < formalAliases.length; index++) {
      assertThat(
              TargetTypedRoomCaseProcessDispatcher.targetIntakeSourceCursorObservation(
                  caseEvent(3 + index, formalAliases[index]), 11))
          .isNull();
    }
    assertThat(
            TargetTypedRoomCaseProcessDispatcher.TARGET_INTAKE_SOURCE_EVENT_CURSOR_CHANGE_ID)
        .isEqualTo("target-intake-source-event-cursor-v1");
    assertThat(
            TargetTypedRoomCaseProcessDispatcher
                .TARGET_INTAKE_COMPLETE_TIMELINE_CURSOR_CHANGE_ID)
        .isEqualTo("target-intake-complete-timeline-cursor-v1");
  }

  @Test
  void globalIntakeProjectionReadyBindsToTheActiveIntakeEpochAndFenceOnly() {
    CaseDomainEventRef readiness = globalCaseEvent(2, "INTAKE_PROJECTION_READY");

    TargetIntakeSourceEventRef cursor =
        TargetIntakeSourceEventRef.fromGlobalIntakeProjectionReady(readiness, 7, 11);

    assertThat(cursor.eventId()).isEqualTo(readiness.eventId());
    assertThat(cursor.eventSequence()).isEqualTo(2);
    assertThat(cursor.eventType()).isEqualTo("INTAKE_PROJECTION_READY");
    assertThat(cursor.roomType()).isEqualTo(RoomType.INTAKE);
    assertThat(cursor.roomEpoch()).isEqualTo(7);
    assertThat(cursor.fencingToken()).isEqualTo(11);
    assertThat(cursor.payloadHash()).isEqualTo(readiness.payloadRef().sha256());

    assertThatThrownBy(
            () ->
                TargetIntakeSourceEventRef.fromGlobalIntakeProjectionReady(
                    globalCaseEvent(3, "CASE_STATUS_CHANGED"), 7, 11))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TargetIntakeSourceEventRef.fromGlobalIntakeProjectionReady(
                    caseEvent(4, "INTAKE_PROJECTION_READY"), 7, 11))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void projectionReadyReceiptHighWaterLoadsUnsignaledGapBeforeRoutingConfirmation() {
    Instant now = Instant.parse("2026-07-30T03:00:00Z");
    ProjectionHighWaterLedger ledger = new ProjectionHighWaterLedger();
    ReadyHighWaterProjectionActivities projection =
        new ReadyHighWaterProjectionActivities(ledger, now);
    HighWaterTargetCaseProcessWorkflow.reset();

    try (TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setInitialTime(now).build())) {
      Worker worker =
          environment.newWorker(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(HighWaterTargetCaseProcessWorkflow.class);
      worker.registerActivitiesImplementations(ledger, projection);
      environment.start();

      ProvisionRoomEpoch provision = highWaterTargetIntakeProvision(now);
      CaseProcessWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CaseProcessWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(provision.caseWorkflowId())
                      .setTaskQueue(CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                      .build());
      WorkflowClient.start(workflow::run, (CaseProcessCarryState) null);
      provision(workflow, provision);

      CaseProcessSnapshot provisioned =
          awaitProcess(environment, workflow, state -> state.activeChildKind() != null);
      assertThat(provisioned.nextCommandSequence()).isEqualTo(1);
      assertThat(provisioned.nextCaseEventSequence()).isEqualTo(1);
      assertThat(provisioned.observedProcessRevision()).isZero();
      assertThat(provisioned.activeRoomRevision()).isZero();

      for (int round = 1; round <= 5; round++) {
        CaseCommandRef message =
            workflowCommand(round, CommandType.INTAKE_MESSAGE, round - 1L, now);
        ledger.put(message);
        workflow.acceptCommand(message);
        int expectedRound = round;
        awaitProcess(
            environment,
            workflow,
            state -> state.nextCommandSequence() == expectedRound + 1L);

        long formalSequence = 1L + (round - 1L) * 3L;
        workflow.domainEventCommitted(
            workflowEvent(
                formalSequence,
                "INTAKE_TURN_READY_TO_CONFIRM",
                RoomType.INTAKE,
                1));
        if (round < 5) {
          long roomSequence = formalSequence + 2L;
          workflow.domainEventCommitted(
              workflowEvent(roomSequence, "ROOM_MESSAGE_CREATED", RoomType.INTAKE, 1));
          awaitProcess(
              environment,
              workflow,
              state -> state.nextCaseEventSequence() == roomSequence + 1L);
        }
      }

      CaseProcessSnapshot recovered =
          awaitProcess(
              environment,
              workflow,
              state ->
                  projection.completionCalls.get() == 5
                      && state.nextCaseEventSequence() == 16);

      assertThat(recovered.highestObservedEventSequence()).isEqualTo(15);
      assertThat(recovered.processedEventCount()).isEqualTo(15);
      assertThat(recovered.activeRoomEpoch()).isEqualTo(1);
      assertThat(recovered.activeFencingToken()).isEqualTo(11);
      assertThat(recovered.observedProcessRevision()).isEqualTo(5);
      assertThat(recovered.activeRoomRevision()).isEqualTo(5);
      assertThat(recovered.protocolErrorCode()).isNull();
      assertThat(ledger.eventLoads)
          .anySatisfy(
              range -> {
                assertThat(range.fromSequenceInclusive()).isEqualTo(14);
                assertThat(range.toSequenceInclusive()).isGreaterThanOrEqualTo(15);
              });
      assertThat(ledger.operations)
          .containsSubsequence(
              "projection:formal:13",
              "durable:event:14",
              "durable:ready:15",
              "projection:receipt-high-water:15",
              "ledger:load:14-15");
      assertThat(HighWaterTargetCaseProcessWorkflow.domainEvents)
          .extracting(CaseDomainEventRef::caseEventSequence)
          .containsExactly(1L, 3L, 4L, 6L, 7L, 9L, 10L, 12L, 13L, 14L);
      assertThat(HighWaterTargetCaseProcessWorkflow.globalReadyEvents)
          .extracting(CaseDomainEventRef::caseEventSequence)
          .containsExactly(2L, 5L, 8L, 11L, 15L);

      CaseCommandRef confirmation = confirmationCommand(now);
      ledger.put(confirmation);
      workflow.acceptCommand(confirmation);

      CaseProcessSnapshot confirmed =
          awaitProcess(environment, workflow, state -> state.nextCommandSequence() == 7);
      assertThat(confirmed.processedCommandCount()).isEqualTo(6);
      assertThat(confirmed.nextCaseEventSequence()).isEqualTo(16);
      assertThat(confirmed.observedProcessRevision()).isEqualTo(6);
      assertThat(confirmed.activeRoomRevision()).isEqualTo(6);
      assertThat(confirmed.activeRoomEpoch()).isEqualTo(1);
      assertThat(confirmed.activeFencingToken()).isEqualTo(11);
      assertThat(confirmed.protocolErrorCode()).isNull();
      assertThat(HighWaterTargetCaseProcessWorkflow.commands)
          .extracting(CaseCommandRef::commandId)
          .containsExactly(
              "command-message-1",
              "command-message-2",
              "command-message-3",
              "command-message-4",
              "command-message-5",
              confirmation.commandId());
      assertThat(ledger.completedRoutingCalls.get()).isEqualTo(6);
    }
  }

  private static CaseProcessSnapshot awaitProcess(
      TestWorkflowEnvironment environment,
      CaseProcessWorkflow workflow,
      Predicate<CaseProcessSnapshot> predicate) {
    for (int attempt = 0; attempt < 200; attempt++) {
      CaseProcessSnapshot state = workflow.state();
      if (predicate.test(state)) {
        return state;
      }
      environment.sleep(Duration.ofMillis(100));
    }
    throw new AssertionError("CaseProcess workflow did not reach the expected state");
  }

  private static ProvisionRoomEpochReceipt provision(
      CaseProcessWorkflow workflow, ProvisionRoomEpoch request) {
    return WorkflowStub.fromTyped(workflow)
        .startUpdate(
            UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                .setUpdateName(CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
                .setUpdateId(request.updateId())
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build(),
            request)
        .getResult();
  }

  private static ProvisionRoomEpoch highWaterTargetIntakeProvision(Instant now) {
    String tenant = "tenant-run001";
    String caseId = "QA_TARGET_INTAKE_1";
    long roomEpoch = 1;
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-target-intake-high-water",
        tenant,
        caseId,
        "room-target-intake-high-water",
        RoomType.INTAKE,
        roomEpoch,
        0,
        0,
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
        0,
        0,
        1,
        1,
        now.plusSeconds(3_600),
        null,
        null,
        now);
  }

  private static CaseCommandRef confirmationCommand(Instant now) {
    return workflowCommand(6, CommandType.INTAKE_CONFIRM, 5, now);
  }

  private static CaseCommandRef workflowCommand(
      int sequence, CommandType commandType, long expectedRevision, Instant now) {
    String commandKind =
        commandType == CommandType.INTAKE_CONFIRM ? "confirm" : "message";
    String hash = Integer.toHexString(sequence % 16).repeat(64);
    return new CaseCommandRef(
        "case-command-ref.v1",
        "command-" + commandKind + "-" + sequence,
        "tenant-run001",
        "QA_TARGET_INTAKE_1",
        sequence,
        commandType,
        RoomType.INTAKE,
        1,
        new ActorRef(
            "party-target",
            ActorRole.USER,
            List.of(
                commandType == CommandType.INTAKE_CONFIRM
                    ? "intake:confirm"
                    : "intake:message")),
        new PayloadRef(
            "intake-command.v1",
            "urn:test:command:" + commandKind + "-" + sequence,
            hash,
            32),
        expectedRevision,
        now.plusSeconds(sequence),
        now.plusSeconds(3_600),
        "00-11111111111111111111111111111111-2222222222222222-01",
        hash);
  }

  private static CaseDomainEventRef workflowEvent(
      long sequence, String eventType, RoomType roomType, long roomEpoch) {
    String hash = Long.toHexString(sequence % 16).repeat(64);
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "EVENT_HIGH_WATER_" + sequence,
        "tenant-run001",
        "QA_TARGET_INTAKE_1",
        sequence,
        eventType,
        roomType,
        roomEpoch,
        new PayloadRef(
            "payload-ref.v1", "urn:after-sale-flow:high-water-event:" + sequence, hash, 0),
        Instant.parse("2026-07-30T03:00:00Z").plusSeconds(sequence),
        "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
  }

  private static TargetIntakePartyScopeSource.ResolvedPartyScopes partyScopes(
      ProvisionRoomEpoch request, ActorRole initiatorRole) {
    TargetIntakePartyScopeSource.Request route =
        new TargetIntakePartyScopeSource.Request(
            request.tenantSurrogate(),
            request.caseId(),
            request.roomEpoch(),
            request.fencingToken());
    boolean userInitiated = initiatorRole == ActorRole.USER;
    return TargetIntakePartyScopeSource.ResolvedPartyScopes.create(
        "p9act.v1." + "a".repeat(32),
        "b".repeat(64),
        route,
        userInitiated ? "user-local" : "merchant-local",
        initiatorRole,
        userInitiated ? "merchant-local" : "user-local",
        userInitiated ? ActorRole.MERCHANT : ActorRole.USER);
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

  private static ProvisionRoomEpoch targetEvidenceProvision() {
    String tenant = "tenant-run001";
    String caseId = "QA_TARGET_EVIDENCE_1";
    long roomEpoch = 2;
    Instant requestedAt = Instant.parse("2026-07-30T02:00:00Z");
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-target-evidence-1",
        tenant,
        caseId,
        "room-target-evidence-1",
        RoomType.EVIDENCE,
        roomEpoch,
        6,
        4,
        19,
        "ACTIVE",
        "EVIDENCE",
        "ACTIVE",
        WriterMode.TEMPORAL,
        CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId),
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.EVIDENCE, roomEpoch),
        TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
        TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
        TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
        "local-d96956b7-case",
        TargetTypedRoomProtocol.workflowType(RoomType.EVIDENCE),
        "local-d96956b7-control",
        TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
        TargetTypedRoomProtocol.STREAM_PROTOCOL,
        9,
        11,
        10,
        12,
        requestedAt.plusSeconds(3_600),
        null,
        null,
        requestedAt);
  }

  private static CaseDomainEventRef caseEvent(long sequence, String eventType) {
    return caseEvent(sequence, eventType, RoomType.INTAKE, 1);
  }

  private static CaseDomainEventRef globalCaseEvent(long sequence, String eventType) {
    return caseEvent(sequence, eventType, null, 0);
  }

  private static CaseDomainEventRef caseEvent(
      long sequence, String eventType, RoomType roomType, long roomEpoch) {
    return new CaseDomainEventRef(
        "case-domain-event-ref.v1",
        "EVENT_TARGET_SOURCE_" + sequence,
        "tenant-run001",
        "QA_TARGET_INTAKE_1",
        sequence,
        eventType,
        roomType,
        roomEpoch,
        new PayloadRef(
            "payload-ref.v1",
            "urn:after-sale-flow:case-event:" + sequence,
            Integer.toString((int) sequence).repeat(64),
            0),
        Instant.parse("2026-07-30T01:00:00Z").plusSeconds(sequence),
        "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
  }

  public static final class HighWaterTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessWorkflow {
    private static final List<CaseCommandRef> commands = new CopyOnWriteArrayList<>();
    private static final List<CaseDomainEventRef> domainEvents = new CopyOnWriteArrayList<>();
    private static final List<CaseDomainEventRef> globalReadyEvents =
        new CopyOnWriteArrayList<>();

    static void reset() {
      commands.clear();
      domainEvents.clear();
      globalReadyEvents.clear();
    }

    @Override
    protected TargetTypedRoomChildHandle startTargetTypedRoomChild(
        ProvisionRoomEpoch request, String provisioningHash) {
      WorkflowExecution execution =
          WorkflowExecution.newBuilder()
              .setWorkflowId(request.roomWorkflowId())
              .setRunId("target-intake-high-water-child-run")
              .build();
      return new RecordingTargetHandle(
          execution,
          request.roomEpoch(),
          request.fencingToken(),
          request.initialProcessRevision(),
          request.initialRoomRevision());
    }

    @Override
    protected TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
        ActiveChildDescriptor descriptor) {
      WorkflowExecution execution =
          WorkflowExecution.newBuilder()
              .setWorkflowId(descriptor.workflowId())
              .setRunId(descriptor.startedRunId())
              .build();
      return new RecordingTargetHandle(
          execution,
          descriptor.roomEpoch(),
          descriptor.fencingToken(),
          descriptor.currentProcessRevision(),
          descriptor.currentRoomRevision());
    }

    private static final class RecordingTargetHandle implements TargetTypedRoomChildHandle {
      private final WorkflowExecution execution;
      private final long roomEpoch;
      private final long fencingToken;
      private long processRevision;
      private long roomRevision;

      private RecordingTargetHandle(
          WorkflowExecution execution,
          long roomEpoch,
          long fencingToken,
          long processRevision,
          long roomRevision) {
        this.execution = execution;
        this.roomEpoch = roomEpoch;
        this.fencingToken = fencingToken;
        this.processRevision = processRevision;
        this.roomRevision = roomRevision;
      }

      @Override
      public WorkflowExecution execution() {
        return execution;
      }

      @Override
      public TargetTypedRoomDispatchReceipt commandAccepted(CaseCommandRef command) {
        requireCurrentRoom(command.roomType(), command.roomEpoch());
        commands.add(command);
        processRevision = Math.max(processRevision, command.expectedProcessRevision() + 1);
        roomRevision++;
        return receipt();
      }

      @Override
      public TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event) {
        requireCurrentRoom(event.roomType(), event.roomEpoch());
        domainEvents.add(event);
        return receipt();
      }

      @Override
      public TargetTypedRoomDispatchReceipt globalIntakeProjectionReady(
          CaseDomainEventRef event) {
        if (event.roomType() != null
            || event.roomEpoch() != 0
            || !"INTAKE_PROJECTION_READY".equals(event.eventType())) {
          throw new IllegalArgumentException("global ready event is not canonical");
        }
        globalReadyEvents.add(event);
        return receipt();
      }

      @Override
      public String initiatorActorScopeHash() {
        return "a".repeat(64);
      }

      @Override
      public String respondentActorScopeHash() {
        return "b".repeat(64);
      }

      @Override
      public void close(String reason) {}

      private TargetTypedRoomDispatchReceipt receipt() {
        return new TargetTypedRoomDispatchReceipt(
            RoomType.INTAKE, roomEpoch, fencingToken, processRevision, roomRevision);
      }

      private void requireCurrentRoom(RoomType actualType, long actualEpoch) {
        if (actualType != RoomType.INTAKE || actualEpoch != roomEpoch) {
          throw new IllegalArgumentException("target handle crossed its room authority");
        }
      }
    }
  }

  private static final class ProjectionHighWaterLedger
      implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {
    private final Map<Long, CaseCommandRef> commands = new ConcurrentHashMap<>();
    private final Map<Long, CaseCommandLedgerState> states = new ConcurrentHashMap<>();
    private final List<CaseDomainEventRef> events = new CopyOnWriteArrayList<>();
    private final List<LoadSequenceRange> eventLoads = new CopyOnWriteArrayList<>();
    private final List<String> operations = new CopyOnWriteArrayList<>();
    private final AtomicInteger completedRoutingCalls = new AtomicInteger();

    void put(CaseCommandRef command) {
      commands.put(command.caseCommandSequence(), command);
      states.put(command.caseCommandSequence(), CaseCommandLedgerState.PENDING_ORCHESTRATION);
    }

    void put(CaseDomainEventRef event, String operation) {
      events.add(event);
      operations.add(operation);
    }

    @Override
    public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
      return commands.values().stream()
          .filter(
              command ->
                  command.caseCommandSequence() >= request.fromSequenceInclusive()
                      && command.caseCommandSequence() <= request.toSequenceInclusive())
          .sorted(Comparator.comparingLong(CaseCommandRef::caseCommandSequence))
          .toList();
    }

    @Override
    public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(
        LoadSequenceRange request) {
      return loadCaseCommands(request).stream()
          .map(
              command ->
                  new CaseCommandLedgerEntry(
                      "case-command-ledger-entry.v1",
                      command,
                      states.get(command.caseCommandSequence())))
          .toList();
    }

    @Override
    public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
      eventLoads.add(request);
      operations.add(
          "ledger:load:"
              + request.fromSequenceInclusive()
              + "-"
              + request.toSequenceInclusive());
      return events.stream()
          .filter(
              event ->
                  event.caseEventSequence() >= request.fromSequenceInclusive()
                      && event.caseEventSequence() <= request.toSequenceInclusive())
          .sorted(Comparator.comparingLong(CaseDomainEventRef::caseEventSequence))
          .toList();
    }

    @Override
    public void reportSequenceGap(SequenceGapReport report) {}

    @Override
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
      return new ExpireCaseCommandResult(
          "expire-case-command-result.v1", CommandLifecycleOutcome.EXPIRED);
    }

    @Override
    public RecordCaseCommandRoutedResult recordCaseCommandRouted(
        RecordCaseCommandRouted request) {
      states.put(request.caseCommandSequence(), CaseCommandLedgerState.ORCHESTRATION_ACCEPTED);
      return new RecordCaseCommandRoutedResult(
          "record-case-command-routed-result.v1",
          CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
    }

    @Override
    public RecordCaseCommandRoutedResult completeCaseCommandRouting(
        RecordCaseCommandRouted request) {
      completedRoutingCalls.incrementAndGet();
      return new RecordCaseCommandRoutedResult(
          "record-case-command-routed-result.v1",
          CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
    }
  }

  private static final class ReadyHighWaterProjectionActivities
      implements ProcessProjectionActivities {
    private final ProjectionHighWaterLedger ledger;
    private final Instant completedAt;
    private final AtomicInteger completionCalls = new AtomicInteger();

    private ReadyHighWaterProjectionActivities(
        ProjectionHighWaterLedger ledger, Instant completedAt) {
      this.ledger = ledger;
      this.completedAt = completedAt;
    }

    @Override
    public ApplyProjectionResult apply(ApplyProjectionCommand command) {
      throw new UnsupportedOperationException("fenced projection is not used by this fixture");
    }

    @Override
    public CompleteConsumedIntakeProjectionResult completeConsumedIntakeProjection(
        CompleteConsumedIntakeProjectionCommand command) {
      completionCalls.incrementAndGet();
      ledger.operations.add("projection:formal:" + command.caseEventSequence());
      long readySequence = command.caseEventSequence() + 1L;
      CaseDomainEventRef ready;
      String readyEventId = null;
      Long readyEventSequence = null;
      if (command.caseEventSequence() == 13) {
        CaseDomainEventRef intervening =
            workflowEvent(14, "CASE_STATUS_CHANGED", RoomType.INTAKE, command.roomEpoch());
        ready = workflowEvent(15, "INTAKE_PROJECTION_READY", null, 0);
        ledger.put(intervening, "durable:event:14");
        ledger.put(ready, "durable:ready:15");
        ledger.operations.add("projection:receipt-high-water:15");
        readyEventId = ready.eventId();
        readyEventSequence = ready.caseEventSequence();
      } else {
        ready = workflowEvent(readySequence, "INTAKE_PROJECTION_READY", null, 0);
        ledger.put(ready, "durable:ready:" + readySequence);
      }
      return new CompleteConsumedIntakeProjectionResult(
          "complete-consumed-intake-projection-result.v1",
          command.eventId(),
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
          "urn:test:intake:projection-high-water",
          "c".repeat(64),
          completedAt,
          readyEventId,
          readyEventSequence);
    }
  }

  public static final class ConcreteTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
