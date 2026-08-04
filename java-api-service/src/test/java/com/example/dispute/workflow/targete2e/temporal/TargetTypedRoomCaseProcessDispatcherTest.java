package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeActorScopes;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource;
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

  public static final class ConcreteTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
