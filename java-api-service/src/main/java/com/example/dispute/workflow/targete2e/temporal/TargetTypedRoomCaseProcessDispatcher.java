package com.example.dispute.workflow.targete2e.temporal;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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

  private static final String INITIATOR_PARTICIPANT = "target-e2e-initiator";
  private static final String RESPONDENT_PARTICIPANT = "target-e2e-respondent";

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
              0,
              0,
              scopeHash("initiator"),
              scopeHash("respondent"));
      case EVIDENCE ->
          new EvidenceHandle(
              Workflow.newExternalWorkflowStub(EvidenceRoomWorkflow.class, execution),
              execution,
              descriptor.roomEpoch(),
              descriptor.fencingToken(),
              0,
              0);
      case HEARING ->
          new CoordinateHandle(
              RoomType.HEARING, execution, descriptor.roomEpoch(), descriptor.fencingToken(), 0, 0);
      case REVIEW ->
          new CoordinateHandle(
              RoomType.REVIEW, execution, descriptor.roomEpoch(), descriptor.fencingToken(), 0, 0);
    };
  }

  private TargetTypedRoomChildHandle startIntake(
      ProvisionRoomEpoch request, String provisioningHash) {
    String initiatorScopeHash = scopeHash("initiator");
    String respondentScopeHash = scopeHash("respondent");
    IntakeRoomStart start =
        new IntakeRoomStart(
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
            "target-e2e-prompt.v1",
            "target-e2e-model.v1",
            "target-e2e-intake-output.v1",
            "target-e2e-policy.v1",
            "target-e2e-guardrail.v1",
            "target-e2e-no-tools.v1",
            initiatorScopeHash,
            respondentScopeHash);
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
        initiatorScopeHash,
        respondentScopeHash);
  }

  private TargetTypedRoomChildHandle startEvidence(ProvisionRoomEpoch request) {
    Instant openedAt = request.requestedAt();
    EvidenceRoomStart start =
        new EvidenceRoomStart(
            "evidence-room-start.v1",
            request.tenantSurrogate(),
            request.caseId(),
            request.roomId(),
            request.roomEpoch(),
            request.fencingToken(),
            INITIATOR_PARTICIPANT,
            RESPONDENT_PARTICIPANT,
            openedAt,
            deadlineAfter(openedAt, request.projectedDeadlineAt()),
            1,
            request.initialProcessRevision(),
            request.initialRoomRevision(),
            request.roomWorkflowBuildId());
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
        request.initialRoomRevision());
  }

  private TargetTypedRoomChildHandle startHearing(ProvisionRoomEpoch request) {
    requirePositiveEpoch(request);
    Instant openedAt = request.requestedAt();
    HearingRoomStart start =
        new HearingRoomStart(
            "hearing-room-start.v1",
            request.tenantSurrogate(),
            request.caseId(),
            request.roomId(),
            request.roomId(),
            request.epochId(),
            HearingWriterMode.TEMPORAL,
            request.roomEpoch(),
            request.fencingToken(),
            INITIATOR_PARTICIPANT,
            RESPONDENT_PARTICIPANT,
            openedAt,
            deadlineAfter(openedAt, request.projectedDeadlineAt()),
            300,
            request.initialProcessRevision(),
            request.initialRoomRevision(),
            request.roomWorkflowBuildId());
    HearingRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            HearingRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new CoordinateHandle(
        RoomType.HEARING,
        execution,
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision());
  }

  private TargetTypedRoomChildHandle startOutcome(
      ProvisionRoomEpoch request, String provisioningHash) {
    requirePositiveEpoch(request);
    Instant openedAt = request.requestedAt();
    String snapshotRef = "urn:target-e2e:projection:" + request.epochId();
    String snapshotHash =
        request.projectionSha256() == null ? provisioningHash : request.projectionSha256();
    OutcomeWorkflowStart start =
        new OutcomeWorkflowStart(
            OutcomeWorkflowStart.SCHEMA_VERSION,
            request.roomWorkflowId(),
            request.caseId(),
            "target-review-" + request.roomEpoch(),
            snapshotRef,
            snapshotHash,
            "urn:target-e2e:adjudication:" + request.epochId(),
            snapshotHash,
            "urn:target-e2e:action:" + request.epochId(),
            snapshotHash,
            "urn:target-e2e:operations:" + request.epochId(),
            snapshotHash,
            0,
            request.roomEpoch(),
            request.initialRoomRevision(),
            request.fencingToken(),
            openedAt,
            deadlineAfter(openedAt, request.projectedDeadlineAt()),
            RuntimeMode.TEMPORAL,
            request.roomWorkflowBuildId(),
            "target-e2e-policy.v1",
            request.graphVersion(),
            "target-e2e-prompt.v1",
            "target-e2e-model.v1",
            false);
    OutcomeRoomWorkflow child =
        Workflow.newChildWorkflowStub(
            OutcomeRoomWorkflow.class, childOptions(request.roomWorkflowId()));
    observe(Async.function(child::run, start));
    WorkflowExecution execution = Workflow.getWorkflowExecution(child).get();
    return new CoordinateHandle(
        RoomType.REVIEW,
        execution,
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision());
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

  private static String scopeHash(String party) {
    return sha256("target-e2e-actor-scope:" + party);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  private static IntakeParty intakeParty(ActorRole role) {
    return switch (role) {
      case USER -> IntakeParty.INITIATOR;
      case MERCHANT -> IntakeParty.RESPONDENT;
      default ->
          throw new IllegalArgumentException(
              "target Intake command requires a USER or MERCHANT actor");
    };
  }

  private static String evidenceParticipant(ActorRole role) {
    return switch (role) {
      case USER -> INITIATOR_PARTICIPANT;
      case MERCHANT -> RESPONDENT_PARTICIPANT;
      default ->
          throw new IllegalArgumentException(
              "target Evidence completion requires a USER or MERCHANT actor");
    };
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
      onCommand(command);
      processRevision =
          Math.max(processRevision, Math.incrementExact(command.expectedProcessRevision()));
      return receipt();
    }

    @Override
    public TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event) {
      requireRoom(event.roomType(), event.roomEpoch());
      onDomainEvent(event);
      return receipt();
    }

    @Override
    public final void close(String reason) {
      ExternalWorkflowStub child = Workflow.newUntypedExternalWorkflowStub(execution);
      child.cancel(reason == null || reason.isBlank() ? "target room closed" : reason);
    }

    protected void onCommand(CaseCommandRef command) {}

    protected void onDomainEvent(CaseDomainEventRef event) {}

    protected final void advanceRoomRevision() {
      roomRevision = Math.incrementExact(roomRevision);
    }

    private TargetTypedRoomDispatchReceipt receipt() {
      return new TargetTypedRoomDispatchReceipt(
          roomType, roomEpoch, fencingToken, processRevision, roomRevision);
    }

    private void requireRoom(RoomType actualType, long actualEpoch) {
      if (actualType != roomType || actualEpoch != roomEpoch) {
        throw new IllegalArgumentException("target typed-room dispatch crossed its fenced room");
      }
    }
  }

  private static final class IntakeHandle extends CoordinateHandle {

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
    protected void onCommand(CaseCommandRef command) {
      IntakeCommandType commandType =
          switch (command.commandType()) {
            case INTAKE_MESSAGE -> IntakeCommandType.INTAKE_MESSAGE;
            case INTAKE_CONFIRM -> IntakeCommandType.INTAKE_CONFIRM;
            case INTAKE_CANCEL -> IntakeCommandType.INTAKE_CANCEL;
            default -> null;
          };
      if (commandType == null) {
        return;
      }
      IntakeParty party = intakeParty(command.actorRef().actorRole());
      String actorScopeHash =
          party == IntakeParty.INITIATOR ? initiatorScopeHash : respondentScopeHash;
      String operationKey =
          switch (commandType) {
            case INTAKE_MESSAGE ->
                IntakeOperationKeys.graphExecute(
                    command.caseId(),
                    roomEpoch,
                    "grt.v1." + command.requestHash().substring(0, 32),
                    command.commandId());
            case INTAKE_CONFIRM ->
                party == IntakeParty.INITIATOR
                    ? IntakeOperationKeys.initiatorAccept(
                        command.caseId(), roomEpoch, command.commandId())
                    : IntakeOperationKeys.respondentConfirm(
                        command.caseId(), roomEpoch, command.commandId());
            case INTAKE_CANCEL ->
                IntakeOperationKeys.cancel(command.caseId(), roomEpoch, command.commandId());
          };
      child.commandAccepted(
          new IntakeWorkflowCommand(
              "intake-workflow-command.v1",
              command.commandId(),
              command.tenantSurrogate(),
              command.caseId(),
              roomEpoch,
              fencingToken,
              command.caseCommandSequence(),
              commandType,
              party,
              actorScopeHash,
              command.payloadRef().uri(),
              command.payloadRef().sha256(),
              operationKey,
              command.requestHash()));
      advanceRoomRevision();
    }
  }

  private static final class EvidenceHandle extends CoordinateHandle {

    private final EvidenceRoomWorkflow child;
    private final long roomEpoch;

    private EvidenceHandle(
        EvidenceRoomWorkflow child,
        WorkflowExecution execution,
        long roomEpoch,
        long fencingToken,
        long processRevision,
        long roomRevision) {
      super(RoomType.EVIDENCE, execution, roomEpoch, fencingToken, processRevision, roomRevision);
      this.child = Objects.requireNonNull(child, "child");
      this.roomEpoch = roomEpoch;
    }

    @Override
    protected void onCommand(CaseCommandRef command) {
      if (command.commandType()
          != com.example.dispute.workflow.contract.v1.ContractTypes.CommandType
              .PARTY_EVIDENCE_COMPLETE) {
        return;
      }
      String participant = evidenceParticipant(command.actorRef().actorRole());
      child.partyCompleted(
          new EvidenceRoomSignal(
              "evidence-room-party-completion.v2",
              participant,
              command.commandId(),
              EvidenceOperationKeys.partyComplete(
                  command.caseId(), roomEpoch, participant, command.commandId()),
              command.requestHash(),
              command.occurredAt()));
      advanceRoomRevision();
    }
  }
}
