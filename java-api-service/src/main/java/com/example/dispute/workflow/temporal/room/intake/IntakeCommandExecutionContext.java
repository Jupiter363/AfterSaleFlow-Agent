package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Reference-only metadata that opts an admitted command into the Temporal Activity path. */
public record IntakeCommandExecutionContext(
    String schemaVersion,
    String threadId,
    String agentSessionId,
    long deadlineEpochMillis,
    RetryBudget retryBudget,
    BranchOperation branchOperation,
    IntakeTargetAgentRunContext targetAgentRun,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long expectedProcessRevision,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long expectedRoomRevision,
    @JsonInclude(JsonInclude.Include.NON_NULL) PinnedVersions branchPinnedVersions) {

  public IntakeCommandExecutionContext(
      String schemaVersion,
      String threadId,
      String agentSessionId,
      long deadlineEpochMillis,
      RetryBudget retryBudget,
      BranchOperation branchOperation,
      IntakeTargetAgentRunContext targetAgentRun) {
    this(
        schemaVersion,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        retryBudget,
        branchOperation,
        targetAgentRun,
        null,
        null,
        null);
  }

  public IntakeCommandExecutionContext(
      String schemaVersion,
      String threadId,
      String agentSessionId,
      long deadlineEpochMillis,
      RetryBudget retryBudget,
      BranchOperation branchOperation) {
    this(
        schemaVersion,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        retryBudget,
        branchOperation,
        null,
        null,
        null,
        null);
  }

  public IntakeCommandExecutionContext(
      String schemaVersion,
      String threadId,
      String agentSessionId,
      long deadlineEpochMillis,
      RetryBudget retryBudget,
      BranchOperation branchOperation,
      IntakeTargetAgentRunContext targetAgentRun,
      Long expectedProcessRevision,
      Long expectedRoomRevision) {
    this(
        schemaVersion,
        threadId,
        agentSessionId,
        deadlineEpochMillis,
        retryBudget,
        branchOperation,
        targetAgentRun,
        expectedProcessRevision,
        expectedRoomRevision,
        null);
  }

  public IntakeCommandExecutionContext {
    if (!"intake-command-execution-context.v1".equals(schemaVersion)
        && !"intake-command-execution-context.v2".equals(schemaVersion)
        && !"intake-command-execution-context.v3".equals(schemaVersion)
        && !"intake-command-execution-context.v4".equals(schemaVersion)
        && !"intake-command-execution-context.v5".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-command-execution-context.v1, v2, v3, v4, or v5");
    }
    requireThreadId(threadId, "threadId");
    requireIdentifier(agentSessionId, "agentSessionId");
    if (deadlineEpochMillis < 1) {
      throw new IllegalArgumentException("deadlineEpochMillis must be positive");
    }
    Objects.requireNonNull(retryBudget, "retryBudget must not be null");
    if ("intake-command-execution-context.v1".equals(schemaVersion) && targetAgentRun != null) {
      throw new IllegalArgumentException("v1 execution context cannot carry target AgentRun state");
    }
    if ("intake-command-execution-context.v2".equals(schemaVersion) && targetAgentRun == null) {
      throw new IllegalArgumentException("v2 execution context requires target AgentRun state");
    }
    if ("intake-command-execution-context.v3".equals(schemaVersion)) {
      if (targetAgentRun != null) {
        throw new IllegalArgumentException("v3 execution context cannot carry target AgentRun state");
      }
      if (branchOperation == null) {
        throw new IllegalArgumentException("v3 execution context requires a branch operation");
      }
    }
    if ("intake-command-execution-context.v4".equals(schemaVersion)
        || "intake-command-execution-context.v5".equals(schemaVersion)) {
      if (targetAgentRun != null) {
        throw new IllegalArgumentException(
            "v4/v5 execution context cannot carry target AgentRun state");
      }
      if (branchOperation == null) {
        throw new IllegalArgumentException("v4/v5 execution context requires a branch operation");
      }
      if (expectedProcessRevision == null
          || expectedRoomRevision == null
          || expectedProcessRevision < 0
          || expectedRoomRevision < 0) {
        throw new IllegalArgumentException(
            "v4/v5 execution context requires non-negative branch authority revisions");
      }
    } else if (expectedProcessRevision != null || expectedRoomRevision != null) {
      throw new IllegalArgumentException(
          "only v4/v5 execution context may carry branch authority revisions");
    }
    if ("intake-command-execution-context.v5".equals(schemaVersion)) {
      if (branchPinnedVersions == null
          || !"intake-pinned-versions.v2".equals(branchPinnedVersions.schemaVersion())) {
        throw new IllegalArgumentException(
            "v5 execution context requires exact target branch private-thread pins");
      }
    } else if (branchPinnedVersions != null) {
      throw new IllegalArgumentException(
          "only v5 execution context may carry target branch private-thread pins");
    }
  }

  @JsonIgnore
  public boolean isTargetAgentRun() {
    return targetAgentRun != null;
  }

  @JsonIgnore
  public boolean isTargetBranch() {
    return "intake-command-execution-context.v3".equals(schemaVersion)
        || "intake-command-execution-context.v4".equals(schemaVersion)
        || "intake-command-execution-context.v5".equals(schemaVersion);
  }

  @JsonIgnore
  public boolean hasPinnedTargetBranchAuthority() {
    return "intake-command-execution-context.v4".equals(schemaVersion)
        || "intake-command-execution-context.v5".equals(schemaVersion);
  }

  @JsonIgnore
  public boolean hasRegisteredTargetBranchPins() {
    return "intake-command-execution-context.v5".equals(schemaVersion);
  }

  void requireCompatible(IntakeCommandType commandType, IntakeParty party) {
    if (targetAgentRun != null && commandType != IntakeCommandType.INTAKE_MESSAGE) {
      throw new IllegalArgumentException("target AgentRun context is valid only for Intake messages");
    }
    BranchOperation expected =
        switch (commandType) {
          case INTAKE_MESSAGE -> null;
          case INTAKE_CANCEL -> BranchOperation.CANCEL;
          case INTAKE_CONFIRM ->
              party == IntakeParty.RESPONDENT ? BranchOperation.RESPONDENT_CONFIRM : null;
        };
    if (commandType == IntakeCommandType.INTAKE_CONFIRM && party == IntakeParty.INITIATOR) {
      if (branchOperation != BranchOperation.INITIATOR_ACCEPT
          && branchOperation != BranchOperation.INITIATOR_REJECT) {
        throw new IllegalArgumentException(
            "initiator confirmation requires an accept or reject branch operation");
      }
      return;
    }
    if (branchOperation != expected) {
      throw new IllegalArgumentException(
          commandType + " from " + party + " has an incompatible branch operation");
    }
  }
}
