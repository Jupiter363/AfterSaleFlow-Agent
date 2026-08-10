package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Acknowledged immutable authority adopted by provider-free finalization recovery. */
public record IntakeAgentRunFinalizationRecoveryResult(
    String schemaVersion,
    IntakeAgentRunFinalizationRecoveryRequest request,
    IntakeAgentRunChildState adoptedChildState,
    IntakeAgentRunFinalizationReadResult finalization,
    @JsonInclude(JsonInclude.Include.NON_NULL) Disposition disposition,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        TargetIntakeCommandTerminalNoCommit terminalNoCommitAuthority) {

  public static final String SCHEMA_VERSION = "intake-agent-run-finalization-recovery-result.v1";
  public static final String V2_SCHEMA_VERSION = "intake-agent-run-finalization-recovery-result.v2";

  public IntakeAgentRunFinalizationRecoveryResult(
      String schemaVersion,
      IntakeAgentRunFinalizationRecoveryRequest request,
      IntakeAgentRunChildState adoptedChildState,
      IntakeAgentRunFinalizationReadResult finalization) {
    this(schemaVersion, request, adoptedChildState, finalization, null, null);
  }

  public IntakeAgentRunFinalizationRecoveryResult {
    boolean terminalNoCommit = V2_SCHEMA_VERSION.equals(schemaVersion);
    if (!SCHEMA_VERSION.equals(schemaVersion) && !terminalNoCommit) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-agent-run-finalization-recovery-result.v1 or .v2");
    }
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(adoptedChildState, "adoptedChildState must not be null");
    Objects.requireNonNull(finalization, "finalization must not be null");
    if (!request.expectedFinalization().equals(finalization)) {
      throw new IllegalArgumentException("recovery result does not match its request authority");
    }
    if (!terminalNoCommit) {
      if (request.isTerminalNoCommitRecovery()
          || disposition != null
          || terminalNoCommitAuthority != null
          || !request.committedChildState().equals(adoptedChildState)) {
        throw new IllegalArgumentException("v1 recovery result does not match committed authority");
      }
    } else {
      if (!request.isTerminalNoCommitRecovery()
          || disposition != Disposition.TERMINAL_NO_COMMIT_CONVERGED
          || terminalNoCommitAuthority == null
          || !request.terminalNoCommitChildState().equals(adoptedChildState)) {
        throw new IllegalArgumentException(
            "v2 recovery result requires terminal convergence authority");
      }
      requireTerminalNoCommitAuthority(request, finalization, terminalNoCommitAuthority);
    }
  }

  private static void requireTerminalNoCommitAuthority(
      IntakeAgentRunFinalizationRecoveryRequest request,
      IntakeAgentRunFinalizationReadResult finalization,
      TargetIntakeCommandTerminalNoCommit authority) {
    IntakeWorkflowCommand command = request.pendingCommand();
    IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
    var graph = target.request().command();
    var message = graph.eventRef();
    var evidence = finalization.terminalNoCommitEvidence();
    var terminal = evidence.terminalResult();
    if (!TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(authority.schemaVersion())
        || !request.tenantSurrogate().equals(authority.tenantSurrogate())
        || !request.caseId().equals(authority.caseId())
        || request.roomEpoch() != authority.roomEpoch()
        || request.fencingToken() != authority.fencingToken()
        || !request.roomWorkflowId().equals(authority.roomWorkflowId())
        || !target.activationId().equals(authority.activationId())
        || !target.activationManifestHash().equals(authority.activationManifestHash())
        || !target.caseBuildId().equals(authority.caseBuildId())
        || !target.controlBuildId().equals(authority.controlBuildId())
        || !target.agentBuildId().equals(authority.agentBuildId())
        || !target.graphBindingHash().equals(authority.graphBindingHash())
        || !target.graphCodeBuildId().equals(authority.graphCodeBuildId())
        || !target.commandHash().equals(authority.commandHash())
        || !target.commandEnvelopeHash().equals(authority.commandEnvelopeHash())
        || !target.request().logicalInputHash().equals(authority.logicalInputHash())
        || !graph.requestHash().equals(authority.agentRunExecutionRequestHash())
        || !command.commandId().equals(authority.commandId())
        || command.sequence() != authority.caseCommandSequence()
        || !command.requestHash().equals(authority.commandRequestHash())
        || message == null
        || !message.artifactId().equals(authority.messageId())
        || !command.payloadRef().equals(authority.messageRef())
        || !command.payloadHash().equals(authority.messageHash())
        || request.sourceProcessRevision() != authority.expectedProcessRevision()
        || authority.newProcessRevision() != Math.incrementExact(request.sourceProcessRevision())
        || request.sourceRoomRevision() != authority.expectedRoomRevision()
        || authority.newRoomRevision() != Math.incrementExact(request.sourceRoomRevision())
        || !request
            .sourceLastCaseEventSequence()
            .equals(authority.expectedLastCaseEventSequence())
        || !evidence.logicalRunId().equals(authority.logicalRunId())
        || !evidence.rootAttemptId().equals(authority.rootAttemptId())
        || !evidence.terminalAttemptId().equals(authority.terminalAttemptId())
        || evidence.terminalAttemptNo() != authority.terminalAttemptNo()
        || evidence.terminalAttemptStatus() != authority.terminalAttemptStatus()
        || terminal.outcome() != authority.agentRunOutcome()
        || !evidence.errorCode().equals(authority.errorCode())
        || terminal.retryable() != authority.retryable()
        || terminal.recoveryAction() != authority.recoveryAction()
        || terminal.lastSequenceNo() != authority.lastSequenceNo()
        || terminal.publicOutputEmitted() != authority.publicOutputEmitted()
        || !terminal.completedAt().equals(authority.terminalAt())) {
      throw new IllegalArgumentException("v2 recovery receipt conflicts with resolved authority");
    }
  }

  public enum Disposition {
    TERMINAL_NO_COMMIT_CONVERGED
  }
}
