package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Exact external authority for provider-free adoption or terminal convergence of finalization. */
public record IntakeAgentRunFinalizationRecoveryRequest(
    String schemaVersion,
    String roomWorkflowId,
    String roomWorkflowRunId,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    IntakeWorkflowCommand pendingCommand,
    IntakeAgentRunChildState childState,
    IntakeAgentRunFinalizationReadResult expectedFinalization,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long sourceProcessRevision,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long sourceRoomRevision,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long sourceLastCaseEventSequence) {

  public static final String SCHEMA_VERSION = "intake-agent-run-finalization-recovery-request.v1";
  public static final String V2_SCHEMA_VERSION =
      "intake-agent-run-finalization-recovery-request.v2";
  public static final String V3_SCHEMA_VERSION =
      "intake-agent-run-finalization-recovery-request.v3";

  public IntakeAgentRunFinalizationRecoveryRequest(
      String schemaVersion,
      String roomWorkflowId,
      String roomWorkflowRunId,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      IntakeWorkflowCommand pendingCommand,
      IntakeAgentRunChildState childState,
      IntakeAgentRunFinalizationReadResult expectedFinalization) {
    this(
        schemaVersion,
        roomWorkflowId,
        roomWorkflowRunId,
        tenantSurrogate,
        caseId,
        roomEpoch,
        fencingToken,
        pendingCommand,
        childState,
        expectedFinalization,
        null,
        null,
        null);
  }

  public IntakeAgentRunFinalizationRecoveryRequest {
    boolean terminalNoCommit = V2_SCHEMA_VERSION.equals(schemaVersion);
    boolean pendingCommittedReceipt = V3_SCHEMA_VERSION.equals(schemaVersion);
    if (!SCHEMA_VERSION.equals(schemaVersion)
        && !terminalNoCommit
        && !pendingCommittedReceipt) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-recovery-request.v1, .v2, or .v3");
    }
    requireIdentifier(roomWorkflowId, "roomWorkflowId");
    requireIdentifier(roomWorkflowRunId, "roomWorkflowRunId");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    if (roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("roomEpoch and fencingToken must be valid");
    }
    String expectedWorkflowId =
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.INTAKE, roomEpoch);
    if (!expectedWorkflowId.equals(roomWorkflowId)) {
      throw new IllegalArgumentException("roomWorkflowId does not match the Intake room authority");
    }
    Objects.requireNonNull(pendingCommand, "pendingCommand must not be null");
    Objects.requireNonNull(childState, "childState must not be null");
    Objects.requireNonNull(expectedFinalization, "expectedFinalization must not be null");
    if (!tenantSurrogate.equals(pendingCommand.tenantSurrogate())
        || !caseId.equals(pendingCommand.caseId())
        || roomEpoch != pendingCommand.roomEpoch()
        || fencingToken != pendingCommand.fencingToken()) {
      throw new IllegalArgumentException("pendingCommand does not match the recovery room scope");
    }
    IntakeCommandExecutionContext execution = pendingCommand.executionContext();
    if (pendingCommand.commandType() != IntakeCommandType.INTAKE_MESSAGE
        || execution == null
        || !execution.isTargetAgentRun()) {
      throw new IllegalArgumentException("recovery requires a target Intake message command");
    }
    childState.requireMatches(pendingCommand, execution.targetAgentRun());
    if (SCHEMA_VERSION.equals(schemaVersion)) {
      if (sourceProcessRevision != null
          || sourceRoomRevision != null
          || sourceLastCaseEventSequence != null) {
        throw new IllegalArgumentException("v1 recovery request must omit source coordinates");
      }
      if (childState.status() != IntakeAgentRunChildState.Status.RESULT_READY) {
        throw new IllegalArgumentException("recovery authority requires a result-ready child");
      }
      if (expectedFinalization.resolution()
          != IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED) {
        throw new IllegalArgumentException("expectedFinalization must be committed");
      }
      expectedFinalization.requireMatches(
          IntakeAgentRunFinalizationReadRequest.winningAttempt(
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
              pendingCommand,
              childState),
          true);
    } else {
      if (sourceProcessRevision == null
          || sourceRoomRevision == null
          || sourceLastCaseEventSequence == null
          || sourceProcessRevision < 0
          || sourceRoomRevision < 0
          || sourceLastCaseEventSequence < 0) {
        throw new IllegalArgumentException(
            "v2/v3 recovery request requires valid source coordinates");
      }
      IntakeTargetAgentRunContext target = execution.targetAgentRun();
      if (sourceProcessRevision != target.expectedProcessRevision()
          || sourceRoomRevision != target.expectedRoomRevision()) {
        throw new IllegalArgumentException(
            "recovery source revisions conflict with the command");
      }
      if (terminalNoCommit) {
        if (childState.status() != IntakeAgentRunChildState.Status.PENDING
            || expectedFinalization.resolution()
                != IntakeAgentRunFinalizationReadResult.Resolution.TERMINAL_NO_COMMIT
            || expectedFinalization.terminalNoCommitEvidence() == null) {
          throw new IllegalArgumentException(
              "v2 recovery requires a pending child and complete terminal-no-commit evidence");
        }
        expectedFinalization.requireMatches(
            IntakeAgentRunFinalizationReadRequest.winningAttempt(
                IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
                pendingCommand,
                childState),
            true);
      } else {
        if (childState.status() != IntakeAgentRunChildState.Status.PENDING
            || expectedFinalization.resolution()
                != IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED) {
          throw new IllegalArgumentException(
              "v3 recovery requires a pending child and committed winning receipt");
        }
        expectedFinalization.requireMatches(
            IntakeAgentRunFinalizationReadRequest.winningAttempt(
                IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
                pendingCommand,
                childState),
            true);
        requirePendingCommittedEventContinuity(
            expectedFinalization,
            sourceProcessRevision,
            sourceRoomRevision,
            sourceLastCaseEventSequence);
      }
    }
  }

  private static void requirePendingCommittedEventContinuity(
      IntakeAgentRunFinalizationReadResult finalization,
      long sourceProcessRevision,
      long sourceRoomRevision,
      long sourceLastCaseEventSequence) {
    var committedEvent = finalization.receipt().committedEvent();
    if (sourceProcessRevision == Long.MAX_VALUE
        || sourceRoomRevision == Long.MAX_VALUE
        || sourceLastCaseEventSequence == Long.MAX_VALUE
        || committedEvent.processRevision() != sourceProcessRevision + 1
        || committedEvent.roomRevision() != sourceRoomRevision + 1
        || committedEvent.eventSequence() != sourceLastCaseEventSequence + 1) {
      throw new IllegalArgumentException(
          "v3 recovery committed event is not the exact successor of its source coordinates");
    }
  }

  public void requireMatches(
      String currentWorkflowId,
      String currentWorkflowRunId,
      IntakeRoomStart currentStart,
      IntakeWorkflowCommand currentCommand,
      IntakeAgentRunChildState currentChild,
      IntakeAgentRunFinalizationReadResult currentFinalization,
      long currentProcessRevision,
      long currentRoomRevision) {
    requireMatches(
        currentWorkflowId,
        currentWorkflowRunId,
        currentStart,
        currentCommand,
        currentChild,
        currentFinalization,
        currentProcessRevision,
        currentRoomRevision,
        null);
  }

  public void requireMatches(
      String currentWorkflowId,
      String currentWorkflowRunId,
      IntakeRoomStart currentStart,
      IntakeWorkflowCommand currentCommand,
      IntakeAgentRunChildState currentChild,
      IntakeAgentRunFinalizationReadResult currentFinalization,
      long currentProcessRevision,
      long currentRoomRevision,
      Long currentLastCaseEventSequence) {
    Objects.requireNonNull(currentStart, "currentStart must not be null");
    Objects.requireNonNull(currentCommand, "currentCommand must not be null");
    Objects.requireNonNull(currentChild, "currentChild must not be null");
    if (!roomWorkflowId.equals(currentWorkflowId)
        || !roomWorkflowRunId.equals(currentWorkflowRunId)
        || !tenantSurrogate.equals(currentStart.tenantSurrogate())
        || !caseId.equals(currentStart.caseId())
        || roomEpoch != currentStart.roomEpoch()
        || fencingToken != currentStart.fencingToken()
        || !pendingCommand.equals(currentCommand)) {
      throw new IllegalArgumentException(
          "recovery request does not match the current room command");
    }
    currentCommand
        .executionContext()
        .targetAgentRun()
        .requireMatches(currentStart, currentCommand, currentProcessRevision, currentRoomRevision);
    if (isTerminalNoCommitRecovery()) {
      if (!childState.equals(currentChild)
          || currentFinalization != null
          || sourceProcessRevision != currentProcessRevision
          || sourceRoomRevision != currentRoomRevision
          || currentLastCaseEventSequence == null
          || !sourceLastCaseEventSequence.equals(currentLastCaseEventSequence)) {
        throw new IllegalArgumentException(
            "v2 recovery request does not match the pending terminal source state");
      }
      return;
    }
    if (isPendingCommittedReceiptRecovery()) {
      boolean sourceCoordinatesMatch =
          sourceProcessRevision == currentProcessRevision
              && sourceRoomRevision == currentRoomRevision
              && currentLastCaseEventSequence != null
              && sourceLastCaseEventSequence.equals(currentLastCaseEventSequence);
      boolean pending = childState.equals(currentChild) && currentFinalization == null;
      boolean alreadyAdopted =
          committedChildState().equals(currentChild)
              && expectedFinalization.equals(currentFinalization);
      if (!sourceCoordinatesMatch || (!pending && !alreadyAdopted)) {
        throw new IllegalArgumentException(
            "v3 recovery request does not match the pending committed source state");
      }
      return;
    }
    boolean resultReady = childState.equals(currentChild) && currentFinalization == null;
    boolean alreadyAdopted =
        committedChildState().equals(currentChild)
            && expectedFinalization.equals(currentFinalization);
    if (!resultReady && !alreadyAdopted) {
      throw new IllegalArgumentException("recovery request does not match the current child state");
    }
  }

  public IntakeAgentRunChildState committedChildState() {
    if (isTerminalNoCommitRecovery()) {
      throw new IllegalStateException("v2 recovery does not adopt a committed child");
    }
    IntakeAgentRunChildState resultReadyChild = childState;
    if (isPendingCommittedReceiptRecovery()) {
      resultReadyChild = childState.resultReady(expectedFinalization.locator().resultHash());
    }
    return resultReadyChild.committed(expectedFinalization, true);
  }

  public IntakeAgentRunChildState terminalNoCommitChildState() {
    if (!isTerminalNoCommitRecovery()) {
      throw new IllegalStateException("v1 recovery does not terminalize a pending child");
    }
    return childState.terminalNoCommit();
  }

  @JsonIgnore
  public boolean isTerminalNoCommitRecovery() {
    return V2_SCHEMA_VERSION.equals(schemaVersion);
  }

  @JsonIgnore
  public boolean isPendingCommittedReceiptRecovery() {
    return V3_SCHEMA_VERSION.equals(schemaVersion);
  }

  public boolean matchesAlreadyAdopted(
      IntakeAgentRunChildState currentChild,
      IntakeAgentRunFinalizationReadResult currentFinalization) {
    return !isTerminalNoCommitRecovery()
        && committedChildState().equals(currentChild)
        && expectedFinalization.equals(currentFinalization);
  }
}
