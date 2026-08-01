package com.example.dispute.workflow.targete2e.temporal;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionResult;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeReviewDecisionAcceptance;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceAgentRunTrigger;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidencePartyCompletionActivities;
import com.example.dispute.workflow.targete2e.rooms.outcome.TargetOutcomeCompletionActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewAgentRunTrigger;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingPort.Binding;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeActorScopes;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.TargetHearingBootstrapActivities;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeCommandBridgeActivities.BindRequest;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource.Request;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource.ResolvedPartyScopes;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic target-lane dispatcher for the four frozen typed room Workflow contracts.
 *
 * <p>The class deliberately remains abstract so the ordinary application artifact cannot register a
 * target-capable CaseProcess implementation. The isolated target source set supplies the only
 * concrete subclass.
 */
public abstract class TargetTypedRoomCaseProcessDispatcher
    extends TargetTypedRoomCaseProcessWorkflow {

  private static final String TARGET_INTAKE_PROMPT_VERSION =
      "all-rooms-prompt.target-e2e.v1";
  private static final String TARGET_INTAKE_MODEL_PROFILE_ID =
      "target-e2e.contract-blocked";
  private static final String TARGET_INTAKE_POLICY_VERSION =
      "all-rooms-policy.target-e2e.v1";
  private static final String TARGET_INTAKE_GUARDRAIL_VERSION =
      "all-rooms-guardrail.target-e2e.v1";
  private static final String TARGET_INTAKE_TOOL_POLICY_VERSION = "tools.none.v1";
  public static final String TARGET_INTAKE_PARTY_SCOPE_AUTHORITY_CHANGE_ID =
      "target-intake-party-scope-authority-v1";
  public static final String TARGET_INTAKE_SOURCE_EVENT_CURSOR_CHANGE_ID =
      "target-intake-source-event-cursor-v1";
  public static final String TARGET_REVIEW_NON_EXECUTION_CHANGE_ID =
      "target-review-non-execution-v1";

  private final TargetIntakeCommandBridgeActivities targetIntakeCommandBridge =
      Workflow.newActivityStub(
          TargetIntakeCommandBridgeActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetIntakePartyScopeSource targetIntakePartyScopes =
      Workflow.newActivityStub(
          TargetIntakePartyScopeSource.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetEvidenceCommandBridgeActivities targetEvidenceCommandBridge =
      Workflow.newActivityStub(
          TargetEvidenceCommandBridgeActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetEvidenceParticipantBindingActivities targetEvidenceParticipantBinding =
      Workflow.newActivityStub(
          TargetEvidenceParticipantBindingActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetEvidencePartyCompletionActivities targetEvidencePartyCompletion =
      Workflow.newActivityStub(
          TargetEvidencePartyCompletionActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(20))
              .setScheduleToCloseTimeout(Duration.ofSeconds(45))
              .build());
  private final TargetReviewCommandBridgeActivities targetReviewCommandBridge =
      Workflow.newActivityStub(
          TargetReviewCommandBridgeActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetReviewOutcomeHandoffActivities targetReviewOutcomeHandoff =
      Workflow.newActivityStub(
          TargetReviewOutcomeHandoffActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetOutcomeCompletionActivities targetOutcomeCompletionActivities =
      Workflow.newActivityStub(
          TargetOutcomeCompletionActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(20))
              .setScheduleToCloseTimeout(Duration.ofSeconds(45))
              .build());
  private final TargetReviewNonExecutionActivities targetReviewNonExecutionActivities =
      Workflow.newActivityStub(
          TargetReviewNonExecutionActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(20))
              .setScheduleToCloseTimeout(Duration.ofSeconds(45))
              .build());
  private final TargetReviewOutcomeStartBindingActivities targetReviewOutcomeStartBinding =
      Workflow.newActivityStub(
          TargetReviewOutcomeStartBindingActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build());
  private final TargetHearingBootstrapActivities targetHearingBootstrap =
      Workflow.newActivityStub(
          TargetHearingBootstrapActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(20))
              .setScheduleToCloseTimeout(Duration.ofSeconds(45))
              .build());

  protected abstract boolean targetArtifactPresent();

  @Override
  protected final TargetTypedRoomChildHandle startTargetTypedRoomChild(
      ProvisionRoomEpoch request, String provisioningHash) {
    requireTargetArtifact();
    Objects.requireNonNull(request, "request");
    requireHash(provisioningHash, "provisioningHash");
    return switch (request.roomType()) {
      case INTAKE -> startIntake(request, provisioningHash);
      case EVIDENCE -> startEvidence(request);
      case HEARING -> startHearing(request);
      case REVIEW -> startOutcome(request, provisioningHash);
    };
  }

  @Override
  protected final TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
      ActiveChildDescriptor descriptor) {
    requireTargetArtifact();
    Objects.requireNonNull(descriptor, "descriptor");
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(descriptor.workflowId())
            .setRunId(descriptor.startedRunId())
            .build();
    return switch (descriptor.roomType()) {
      case INTAKE ->
          new IntakeHandle(
              Workflow.newExternalWorkflowStub(IntakeRoomWorkflow.class, execution),
              execution,
              descriptor.roomEpoch(),
              descriptor.fencingToken(),
              descriptor.currentProcessRevision(),
              descriptor.currentRoomRevision(),
              descriptor.initiatorActorScopeHash(),
              descriptor.respondentActorScopeHash());
      case EVIDENCE ->
          new EvidenceHandle(
              Workflow.newExternalWorkflowStub(EvidenceRoomWorkflow.class, execution),
              execution,
              descriptor.roomEpoch(),
              descriptor.fencingToken(),
              descriptor.currentProcessRevision(),
              descriptor.currentRoomRevision(),
              Objects.requireNonNull(
                  descriptor.evidenceParticipantBinding(),
                  "restored target Evidence participant binding"),
              descriptor.roomWorkflowBuildId(),
              ExecutionLane.TARGET_E2E_CANDIDATE);
      case HEARING ->
          new HearingHandle(
              Workflow.newExternalWorkflowStub(HearingRoomWorkflow.class, execution),
              execution,
              descriptor.roomEpoch(),
              descriptor.fencingToken(),
              descriptor.currentProcessRevision(),
              descriptor.currentRoomRevision());
      case REVIEW ->
          new ReviewHandle(
              Workflow.newExternalWorkflowStub(OutcomeRoomWorkflow.class, execution),
              execution,
              descriptor.roomEpoch(),
              descriptor.fencingToken(),
              descriptor.currentProcessRevision(),
              descriptor.currentRoomRevision(),
              Objects.requireNonNull(
                  descriptor.reviewOutcomeStartBinding(),
                  "restored target Review Outcome binding"));
    };
  }

  private TargetTypedRoomChildHandle startIntake(
      ProvisionRoomEpoch request, String provisioningHash) {
    int partyScopeAuthorityVersion =
        Workflow.getVersion(
            TARGET_INTAKE_PARTY_SCOPE_AUTHORITY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    IntakeRoomStart start;
    if (partyScopeAuthorityVersion == Workflow.DEFAULT_VERSION) {
      start = legacyTargetIntakeStart(request);
    } else {
      Request partyScopeRequest = partyScopeRequest(request);
      ResolvedPartyScopes partyScopes = targetIntakePartyScopes.resolve(partyScopeRequest);
      start = targetIntakeStart(request, partyScopes);
    }
    IntakeRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            IntakeRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new IntakeHandle(
        child,
        execution,
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        start.initiatorActorScopeHash(),
        start.respondentActorScopeHash());
  }

  static IntakeRoomStart targetIntakeStart(
      ProvisionRoomEpoch request, ResolvedPartyScopes partyScopes) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(partyScopes, "partyScopes").requireMatches(partyScopeRequest(request));
    return targetIntakeStart(
        request,
        partyScopes.initiator().actorScopeHash(),
        partyScopes.respondent().actorScopeHash());
  }

  static IntakeRoomStart legacyTargetIntakeStart(ProvisionRoomEpoch request) {
    Objects.requireNonNull(request, "request");
    return targetIntakeStart(
        request,
        TargetIntakeActorScopes.hash(request.caseId(), "user-local", ActorRole.USER),
        TargetIntakeActorScopes.hash(
            request.caseId(), "merchant-local", ActorRole.MERCHANT));
  }

  private static IntakeRoomStart targetIntakeStart(
      ProvisionRoomEpoch request,
      String initiatorActorScopeHash,
      String respondentActorScopeHash) {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        request.roomWorkflowBuildId(),
        request.graphVersion(),
        request.checkpointSchemaVersion(),
        TARGET_INTAKE_PROMPT_VERSION,
        TARGET_INTAKE_MODEL_PROFILE_ID,
        "target-e2e-intake-output.v1",
        TARGET_INTAKE_POLICY_VERSION,
        TARGET_INTAKE_GUARDRAIL_VERSION,
        TARGET_INTAKE_TOOL_POLICY_VERSION,
        initiatorActorScopeHash,
        respondentActorScopeHash);
  }

  private static Request partyScopeRequest(ProvisionRoomEpoch request) {
    return new Request(
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken());
  }

  static TargetIntakeSourceEventRef targetIntakeSourceCursorObservation(
      CaseDomainEventRef event, long fencingToken) {
    Objects.requireNonNull(event, "event");
    return TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED.equals(event.eventType())
        ? TargetIntakeSourceEventRef.from(event, fencingToken)
        : null;
  }

  private TargetTypedRoomChildHandle startEvidence(ProvisionRoomEpoch request) {
    TargetEvidenceParticipantBindingActivities.Binding participants =
        targetEvidenceParticipantBinding.bind(
            new TargetEvidenceParticipantBindingActivities.Request(
                request.tenantSurrogate(),
                request.caseId(),
                request.roomEpoch(),
                request.fencingToken()));
    EvidenceRoomStart start = targetEvidenceStart(request, participants);
    EvidenceRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            EvidenceRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new EvidenceHandle(
        child,
        execution,
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        participants,
        start.workflowBuildId(),
        start.executionLane());
  }

  static EvidenceRoomStart targetEvidenceStart(
      ProvisionRoomEpoch request,
      TargetEvidenceParticipantBindingActivities.Binding participants) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(participants, "participants");
    Instant openedAt = request.requestedAt();
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomId(),
        request.roomEpoch(),
        request.fencingToken(),
        participants.initiatorParticipantId(),
        participants.respondentParticipantId(),
        openedAt,
        deadlineAfter(openedAt, request.projectedDeadlineAt()),
        1,
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.roomWorkflowBuildId(),
        ExecutionLane.TARGET_E2E_CANDIDATE);
  }

  private TargetTypedRoomChildHandle startHearing(ProvisionRoomEpoch request) {
    requireNonNegativeEpoch(request);
    TargetHearingBootstrapActivities.Binding binding = targetHearingBootstrap.bootstrap(request);
    requireExactHearingBinding(request, binding);
    Instant openedAt = request.requestedAt();
    HearingRoomStart start =
        new HearingRoomStart(
            "hearing-room-start.v1",
            request.tenantSurrogate(),
            request.caseId(),
            request.roomId(),
            binding.flowInstanceId(),
            binding.epochId(),
            HearingWriterMode.TEMPORAL,
            binding.roomEpoch(),
            binding.fencingToken(),
            binding.initiatorParticipantId(),
            binding.respondentParticipantId(),
            openedAt,
            deadlineAfter(openedAt, request.projectedDeadlineAt()),
            300,
            binding.processRevision(),
            binding.roomRevision(),
            request.roomWorkflowBuildId());
    HearingRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            HearingRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new HearingHandle(
        child,
        execution,
        binding.roomEpoch(),
        binding.fencingToken(),
        binding.processRevision(),
        binding.roomRevision());
  }

  private TargetTypedRoomChildHandle startOutcome(
      ProvisionRoomEpoch request, String provisioningHash) {
    requirePositiveEpoch(request);
    Binding binding = targetReviewOutcomeStartBinding.bind(request).binding();
    OutcomeWorkflowStart start = binding.start();
    OutcomeRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            OutcomeRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new ReviewHandle(
        child,
        execution,
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        binding);
  }

  private void requireTargetArtifact() {
    if (!targetArtifactPresent()) {
      throw new IllegalStateException("target typed-room dispatcher requires the target artifact");
    }
  }

  private static ChildWorkflowOptions childOptions(String workflowId) {
    return ChildWorkflowOptions.newBuilder()
        .setWorkflowId(workflowId)
        .setTaskQueue(ROOM_CONTROL_TASK_QUEUE)
        .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
        .build();
  }

  private static void observe(Promise<?> completion) {
    completion.exceptionally(ignored -> null);
  }

  private static Instant deadlineAfter(Instant openedAt, Instant configuredDeadline) {
    if (configuredDeadline != null && configuredDeadline.isAfter(openedAt)) {
      return configuredDeadline;
    }
    return openedAt.plus(Duration.ofHours(24));
  }

  private static void requirePositiveEpoch(ProvisionRoomEpoch request) {
    if (request.roomEpoch() < 1) {
      throw new IllegalArgumentException(
          request.roomType() + " target room epoch must be positive");
    }
  }

  private static void requireNonNegativeEpoch(ProvisionRoomEpoch request) {
    if (request.roomEpoch() < 0) {
      throw new IllegalArgumentException(
          request.roomType() + " target room epoch must not be negative");
    }
  }

  private static void requireExactHearingBinding(
      ProvisionRoomEpoch request, TargetHearingBootstrapActivities.Binding binding) {
    binding = Objects.requireNonNull(binding, "target Hearing bootstrap binding");
    if (!request.roomId().equals(binding.flowInstanceId())
        || !request.epochId().equals(binding.epochId())
        || request.roomEpoch() != binding.roomEpoch()
        || request.fencingToken() != binding.fencingToken()
        || request.initialProcessRevision() != binding.processRevision()
        || request.initialRoomRevision() != binding.roomRevision()
        || !"COURT_PREPARING".equals(binding.stageCode())
        || binding.stageSequence() != 1) {
      throw new IllegalStateException("target Hearing bootstrap coordinates drifted");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  private static class CoordinateHandle implements TargetTypedRoomChildHandle {

    private final RoomType roomType;
    private final WorkflowExecution execution;
    private final long roomEpoch;
    private final long fencingToken;
    private long processRevision;
    private long roomRevision;

    private CoordinateHandle(
        RoomType roomType,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision) {
      this.roomType = Objects.requireNonNull(roomType, "roomType");
      this.execution = Objects.requireNonNull(execution, "execution");
      this.roomEpoch = roomEpoch;
      this.fencingToken = fencingToken;
      this.processRevision = processRevision;
      this.roomRevision = roomRevision;
    }

    @Override
    public final WorkflowExecution execution() {
      return execution;
    }

    @Override
    public TargetTypedRoomDispatchReceipt commandAccepted(CaseCommandRef command) {
      requireRoom(command.roomType(), command.roomEpoch());
      if (!onCommand(command)) {
        throw new IllegalArgumentException("target typed-room command is not accepted in the current state");
      }
      if (advanceCoordinatesOnCommand(command)) {
        processRevision =
            Math.max(processRevision, Math.incrementExact(command.expectedProcessRevision()));
      }
      return targetReceipt();
    }

    @Override
    public TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event) {
      requireRoom(event.roomType(), event.roomEpoch());
      onDomainEvent(event);
      return targetReceipt();
    }

    @Override
    public final void close(String reason) {
      ExternalWorkflowStub child = Workflow.newUntypedExternalWorkflowStub(execution);
      child.cancel(reason == null || reason.isBlank() ? "target room closed" : reason);
    }

    protected boolean onCommand(CaseCommandRef command) {
      return false;
    }

    protected void onDomainEvent(CaseDomainEventRef event) {}

    /** Hearing formal receipts, not browser acknowledgement, are its authoritative coordinates. */
    protected boolean advanceCoordinatesOnCommand(CaseCommandRef command) {
      return true;
    }

    protected final void advanceRoomRevision() {
      roomRevision = Math.incrementExact(roomRevision);
    }

    protected final void advanceTo(TargetRoomProgressReceipt progress) {
      if (progress.processRevision() != Math.incrementExact(processRevision)
          || progress.roomRevision() != Math.incrementExact(roomRevision)) {
        throw new IllegalStateException("target room durable acknowledgement coordinates are invalid");
      }
      processRevision = progress.processRevision();
      roomRevision = progress.roomRevision();
    }

    /** A terminal Outcome may condense multiple durable facts into one parent transition. */
    protected final void advanceToTerminal(TargetRoomProgressReceipt progress) {
      if (progress.processRevision() <= processRevision || progress.roomRevision() <= roomRevision) {
        throw new IllegalStateException("target terminal acknowledgement coordinates are invalid");
      }
      processRevision = progress.processRevision();
      roomRevision = progress.roomRevision();
    }

    protected final void requireCurrent(TargetRoomProgressReceipt progress) {
      if (progress.roomType() != roomType
          || progress.roomEpoch() != roomEpoch
          || progress.fencingToken() != fencingToken
          || progress.processRevision() != processRevision
          || progress.roomRevision() != roomRevision) {
        throw new IllegalStateException("target post-routing acknowledgement coordinates are invalid");
      }
    }

    protected final long roomRevision() {
      return roomRevision;
    }

    protected final long processRevision() {
      return processRevision;
    }

    protected final long fencingToken() {
      return fencingToken;
    }

    @Override
    public String initiatorActorScopeHash() {
      return null;
    }

    @Override
    public String respondentActorScopeHash() {
      return null;
    }

    protected TargetTypedRoomDispatchReceipt targetReceipt() {
      return new TargetTypedRoomDispatchReceipt(
          roomType, roomEpoch, fencingToken, processRevision, roomRevision);
    }

    protected final long roomEpoch() {
      return roomEpoch;
    }

    private void requireRoom(RoomType actualType, long actualEpoch) {
      if (actualType != roomType || actualEpoch != roomEpoch) {
        throw new IllegalArgumentException("target typed-room dispatch crossed its fenced room");
      }
    }
  }

  private final class IntakeHandle extends CoordinateHandle {

    private final IntakeRoomWorkflow child;
    private final long roomEpoch;
    private final long fencingToken;
    private final String initiatorScopeHash;
    private final String respondentScopeHash;

    private IntakeHandle(
        IntakeRoomWorkflow child,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision,
        String initiatorScopeHash,
        String respondentScopeHash) {
      super(RoomType.INTAKE, execution, roomEpoch, fencingToken, processRevision, roomRevision);
      this.child = Objects.requireNonNull(child, "child");
      this.roomEpoch = roomEpoch;
      this.fencingToken = fencingToken;
      this.initiatorScopeHash = initiatorScopeHash;
      this.respondentScopeHash = respondentScopeHash;
    }

    @Override
    protected boolean onCommand(CaseCommandRef command) {
      if (command.commandType()
              != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.INTAKE_MESSAGE
          && command.commandType()
              != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.INTAKE_CONFIRM
          && command.commandType()
              != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.INTAKE_CANCEL) {
        return false;
      }
      IntakeWorkflowCommand bound =
          targetIntakeCommandBridge.bindCommand(new BindRequest(command, fencingToken, roomRevision()));
      child.commandAccepted(bound);
      advanceRoomRevision();
      return true;
    }

    @Override
    protected void onDomainEvent(CaseDomainEventRef event) {
      TargetIntakeSourceEventRef sourceEvent =
          targetIntakeSourceCursorObservation(event, fencingToken);
      if (sourceEvent == null) {
        return;
      }
      int sourceCursorVersion =
          Workflow.getVersion(
              TARGET_INTAKE_SOURCE_EVENT_CURSOR_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
      if (sourceCursorVersion == Workflow.DEFAULT_VERSION) {
        return;
      }
      child.targetSourceEventObserved(sourceEvent);
    }

    @Override
    public String initiatorActorScopeHash() {
      return initiatorScopeHash;
    }

    @Override
    public String respondentActorScopeHash() {
      return respondentScopeHash;
    }
  }

  private final class HearingHandle extends CoordinateHandle {

    private final HearingRoomWorkflow child;

    private HearingHandle(
        HearingRoomWorkflow child,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision) {
      super(RoomType.HEARING, execution, roomEpoch, fencingToken, processRevision, roomRevision);
      this.child = Objects.requireNonNull(child, "child");
    }

    @Override
    protected boolean onCommand(CaseCommandRef command) {
      if (!isPartyCommand(command.commandType())) {
        return false;
      }
      child.partyCommandAccepted(
          new HearingPartyCommand(
              command, fencingToken(), processRevision(), roomRevision()));
      return true;
    }

    @Override
    protected boolean advanceCoordinatesOnCommand(CaseCommandRef command) {
      return false;
    }

    private static boolean isPartyCommand(
        com.example.dispute.workflow.contract.v1.ContractTypes.CommandType commandType) {
      return commandType
              == com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.HEARING_STATEMENT
          || commandType
              == com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.HEARING_EVIDENCE_BATCH;
    }
  }

  /**
   * Binds an admitted Review command to its advisory AgentRun without allowing Graph output to
   * produce a business decision. The finalization lane must relay the separately persisted human
   * decision receipt to {@link OutcomeRoomWorkflow#reviewDecisionCommitted}; this dispatcher has
   * no receipt source and therefore deliberately never synthesizes that signal.
   */
  private final class ReviewHandle extends CoordinateHandle {

    private final OutcomeRoomWorkflow outcomeChild;
    private final Binding binding;
    private boolean executionAuthorized;
    private boolean terminalClosed;
    private boolean sourceTransitionClosed;
    private String acceptedReviewCommandId;
    private String acceptedReviewReceiptId;
    private String acceptedReviewReceiptHash;
    private long acceptedReviewReceiptRevision;
    private com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt
        acceptedReviewDecision;
    private TargetRoomProgressReceipt terminalProgressReceipt;

    private ReviewHandle(
        OutcomeRoomWorkflow outcomeChild,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision,
        Binding binding) {
      super(RoomType.REVIEW, execution, roomEpoch, fencingToken, processRevision, roomRevision);
      this.outcomeChild = Objects.requireNonNull(outcomeChild, "outcomeChild");
      this.binding = binding;
    }

    @Override
    protected boolean onCommand(CaseCommandRef command) {
      if (command.commandType()
          != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.REVIEW_DECISION) {
        return false;
      }
      TargetReviewAgentRunTrigger trigger =
          targetReviewCommandBridge.bind(
              new TargetReviewCommandBridgeActivities.BindRequest(
                  command, fencingToken(), roomRevision()));
      TargetReviewOutcomeHandoffActivities.RelayResult handoff =
          targetReviewOutcomeHandoff.relay(
              new TargetReviewOutcomeHandoffActivities.RelayRequest(
                  trigger.activationId(),
                  trigger.activationManifestHash(),
                  command.tenantSurrogate(),
                  command.caseId(),
                  command.commandId(),
                  command.roomEpoch(),
                  fencingToken()));
      Objects.requireNonNull(binding, "target Review Outcome start binding");
      binding.requireCompatible(handoff.outcomeReceipt());
      OutcomeReviewDecisionAcceptance acceptance =
          outcomeChild.reviewDecisionAccepted(handoff.outcomeReceipt());
      if (!acceptance.accepted()
          || !handoff.outcomeReceipt().receiptId().equals(acceptance.receiptId())
          || !handoff.outcomeReceipt().receiptHash().equals(acceptance.receiptHash())
          || handoff.outcomeReceipt().sourceRevision() != acceptance.sourceRevision()
          || handoff.outcomeReceipt().revision() != acceptance.acceptedRevision()) {
        return false;
      }
      executionAuthorized = handoff.outcomeReceipt().executionAuthorized();
      acceptedReviewCommandId = command.commandId();
      acceptedReviewReceiptId = handoff.outcomeReceipt().receiptId();
      acceptedReviewReceiptHash = handoff.outcomeReceipt().receiptHash();
      acceptedReviewReceiptRevision = handoff.outcomeReceipt().revision();
      acceptedReviewDecision = handoff.outcomeReceipt();
      // The Java-owned decision is authoritative. Advisory Graph execution is intentionally
      // detached so an advisory outage cannot block or alter Outcome progression.
      Async.procedure(
          () -> {
            try {
              launchTargetAgentRunChild(trigger.request());
            } catch (RuntimeException ignored) {
              // AgentRun finalization owns durable advisory failure recording.
            }
          });
      advanceRoomRevision();
      return true;
    }

    @Override
    public TargetTypedRoomDispatchReceipt postRouting(CaseCommandRef command) {
      if (command.commandType()
              != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.REVIEW_DECISION
          || !Objects.equals(acceptedReviewCommandId, command.commandId())) {
        throw new IllegalArgumentException("target Review completion does not match its accepted command");
      }
      if (!executionAuthorized) {
        int version =
            Workflow.getVersion(
                TARGET_REVIEW_NON_EXECUTION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        if (version == Workflow.DEFAULT_VERSION) {
          return null;
        }
        TargetReviewNonExecutionActivities.CompletionResult completion =
            targetReviewNonExecutionActivities.complete(
                new TargetReviewNonExecutionActivities.CompletionRequest(
                    binding.start(),
                    Objects.requireNonNull(
                        acceptedReviewDecision, "accepted target Review non-execution decision"),
                    command,
                    command.expectedProcessRevision(),
                    binding.start().revision()));
        TargetRoomProgressReceipt progress = completion.sourceProgressReceipt();
        requireCurrent(progress);
        terminalProgressReceipt = progress;
        terminalClosed = completion.terminalCaseProcess();
        sourceTransitionClosed = !terminalClosed;
        return targetReceipt();
      }
      OutcomeCompletionResult completion = outcomeChild.completeTargetOutcomeAfterRouting(
          new OutcomeCompletionRequest(
              RoomType.REVIEW,
              roomEpoch(),
              fencingToken(),
              processRevision(),
              roomRevision(),
              acceptedReviewReceiptId,
              acceptedReviewReceiptHash,
              acceptedReviewReceiptRevision));
      TargetRoomProgressReceipt progress = completion.terminalProgressReceipt();
      advanceToTerminal(progress);
      terminalProgressReceipt = progress;
      terminalClosed = true;
      return targetReceipt();
    }

    @Override
    public TargetTypedRoomDispatchReceipt recoverAppliedTerminal(CaseCommandRef command) {
      if (command.commandType()
          != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.REVIEW_DECISION) {
        return null;
      }
      Objects.requireNonNull(binding, "target Review Outcome start binding");
      TargetReviewAgentRunTrigger trigger =
          targetReviewCommandBridge.bind(
              new TargetReviewCommandBridgeActivities.BindRequest(
                  // The handle persisted R+1 after accepting the decision. Recovery must bind
                  // the command material's frozen R, which is the Review start source revision.
                  command, fencingToken(), binding.start().revision()));
      TargetReviewOutcomeHandoffActivities.RelayResult handoff =
          targetReviewOutcomeHandoff.relay(
              new TargetReviewOutcomeHandoffActivities.RelayRequest(
                  trigger.activationId(),
                  trigger.activationManifestHash(),
                  command.tenantSurrogate(),
                  command.caseId(),
                  command.commandId(),
                  command.roomEpoch(),
                  fencingToken()));
      binding.requireCompatible(handoff.outcomeReceipt());
      if (!handoff.outcomeReceipt().executionAuthorized()) {
        TargetReviewNonExecutionActivities.CompletionResult completion =
            targetReviewNonExecutionActivities.loadApplied(
                new TargetReviewNonExecutionActivities.LoadRequest(
                    binding.start(), handoff.outcomeReceipt(), command));
        TargetRoomProgressReceipt progress = completion.sourceProgressReceipt();
        advanceTo(progress);
        terminalProgressReceipt = progress;
        terminalClosed = completion.terminalCaseProcess();
        sourceTransitionClosed = !terminalClosed;
        return targetReceipt();
      }
      TargetRoomProgressReceipt progress = targetOutcomeCompletionActivities.loadTerminalProgress(
          new TargetOutcomeCompletionActivities.TerminalProgressRequest(
              binding.start().workflowId(),
              binding.start().caseId(),
              binding.start().epoch(),
              binding.start().fence(),
              handoff.outcomeReceipt().receiptId(),
              handoff.outcomeReceipt().receiptHash(),
              handoff.outcomeReceipt().revision()));
      advanceToTerminal(progress);
      terminalProgressReceipt = progress;
      terminalClosed = true;
      return targetReceipt();
    }

    @Override
    public boolean terminalAfterPostRouting() {
      return terminalClosed;
    }

    @Override
    public boolean sourceTransitionAfterPostRouting() {
      return sourceTransitionClosed;
    }

    @Override
    public TargetRoomProgressReceipt terminalProgressReceipt() {
      return terminalProgressReceipt;
    }

    @Override
    protected void onDomainEvent(CaseDomainEventRef event) {
      throw new IllegalStateException(
          "target Review receipt relay requires a durable OutcomeReviewDecisionReceipt signal");
    }

    @Override
    public Binding reviewOutcomeStartBinding() {
      return binding;
    }
  }

  private final class EvidenceHandle extends CoordinateHandle {

    private final EvidenceRoomWorkflow child;
    private final long roomEpoch;
    private final TargetEvidenceParticipantBindingActivities.Binding participants;
    private final String workflowBuildId;
    private final ExecutionLane executionLane;

    private EvidenceHandle(
        EvidenceRoomWorkflow child,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision,
        TargetEvidenceParticipantBindingActivities.Binding participants,
        String workflowBuildId,
        ExecutionLane executionLane) {
      super(RoomType.EVIDENCE, execution, roomEpoch, fencingToken, processRevision, roomRevision);
      this.child = Objects.requireNonNull(child, "child");
      this.roomEpoch = roomEpoch;
      this.participants = Objects.requireNonNull(participants, "participants");
      this.workflowBuildId = Objects.requireNonNull(workflowBuildId, "workflowBuildId");
      if (executionLane != ExecutionLane.TARGET_E2E_CANDIDATE) {
        throw new IllegalArgumentException("target Evidence handle requires the target execution lane");
      }
      this.executionLane = executionLane;
    }

    @Override
    protected boolean onCommand(CaseCommandRef command) {
      if (command.commandType()
          == com.example.dispute.workflow.contract.v1.ContractTypes.CommandType.EVIDENCE_SUBMIT) {
        TargetEvidenceAgentRunTrigger trigger =
            targetEvidenceCommandBridge.bindEvidenceAgentRun(
                new TargetEvidenceCommandBridgeActivities.BindRequest(
                    command, fencingToken(), roomRevision()));
        ExecuteAgentRunResult result = launchTargetAgentRunChild(trigger.request());
        child.agentRunFinalized(
            TargetRoomAgentRunFinalizationReceipt.completed(
                trigger.request(), result, fencingToken(), trigger.expectedRoomRevision()));
        advanceRoomRevision();
        return true;
      }
      if (command.commandType()
          == com.example.dispute.workflow.contract.v1.ContractTypes.CommandType
              .PARTY_EVIDENCE_COMPLETE) {
        String participant = participantFor(command.actorRef().actorId());
        TargetEvidencePartyCompletionActivities.Result finalized =
            targetEvidencePartyCompletion.finalizeCompletion(
                new TargetEvidencePartyCompletionActivities.Request(
                    evidenceStart(), participants, command, processRevision(), roomRevision()));
        advanceTo(finalized.progressReceipt());
        child.partyCompleted(
            new EvidenceRoomSignal(
                "evidence-room-party-completion.v2",
                participant,
                command.commandId(),
                EvidenceOperationKeys.partyComplete(
                    command.caseId(), roomEpoch, participant, command.commandId()),
                command.requestHash(),
                command.occurredAt()));
        return true;
      }
      return false;
    }

    @Override
    public TargetEvidenceParticipantBindingActivities.Binding evidenceParticipantBinding() {
      return participants;
    }

    private String participantFor(String actorId) {
      if (participants.initiatorParticipantId().equals(actorId)) {
        return participants.initiatorParticipantId();
      }
      if (participants.respondentParticipantId().equals(actorId)) {
        return participants.respondentParticipantId();
      }
      throw new IllegalArgumentException("target Evidence completion actor is not a bound participant");
    }

    private EvidenceRoomStart evidenceStart() {
      return new EvidenceRoomStart(
          "evidence-room-start.v1",
          participants.tenantSurrogate(),
          participants.caseId(),
          "ROOM_EVIDENCE_" + participants.caseId(),
          roomEpoch,
          fencingToken(),
          participants.initiatorParticipantId(),
          participants.respondentParticipantId(),
          Instant.EPOCH,
          Instant.EPOCH.plus(Duration.ofHours(24)),
          1,
          processRevision(),
          roomRevision(),
          workflowBuildId,
          executionLane);
    }
  }

  /** Runs the immutable child through formal finalization before returning its completed result. */
  private ExecuteAgentRunResult launchTargetAgentRunChild(ExecuteAgentRunRequest request) {
    Duration remaining = remainingAgentRunDeadline(request);
    AgentRunWorkflow child =
        Workflow.newChildWorkflowStub(
            AgentRunWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(targetAgentRunWorkflowId(request))
                .setTaskQueue(AGENT_EXECUTION)
                .setWorkflowExecutionTimeout(remaining)
                .setWorkflowRunTimeout(remaining)
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
                .build());
    return child.run(request);
  }

  private static Duration remainingAgentRunDeadline(ExecuteAgentRunRequest request) {
    long remainingMillis = request.command().deadlineAt().toEpochMilli() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      throw new IllegalArgumentException("target AgentRun trigger deadline has elapsed");
    }
    return Duration.ofMillis(remainingMillis);
  }

  private static String targetAgentRunWorkflowId(ExecuteAgentRunRequest request) {
    return AgentRunWorkflowIds.forLogicalRun(request.logicalRunId());
  }
}
