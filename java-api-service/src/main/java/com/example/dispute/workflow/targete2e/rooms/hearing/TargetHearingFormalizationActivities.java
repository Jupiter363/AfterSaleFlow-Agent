package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * CONTROL-side execution contract for Java-owned Hearing formalization. Implementations must
 * persist and return formal receipts; the workflow may only relay the returned receipts.
 */
@ActivityInterface
public interface TargetHearingFormalizationActivities {

  @ActivityMethod(name = "BootstrapTargetHearingStage")
  StageResult bootstrapNext(TransitionRequest request);

  @ActivityMethod(name = "FormalizeTargetHearingPartyAction")
  PartyResult formalizeParty(PartyRequest request);

  @ActivityMethod(name = "PrepareTargetHearingAgentStage")
  AgentStagePreparation prepareAgentStage(TransitionRequest request);

  @ActivityMethod(name = "FinalizeTargetHearingAgentStage")
  AgentStageResult finalizeAgentStage(AgentStageFinalizationRequest request);

  @ActivityMethod(name = "FreezeTargetHearingDossier")
  StageResult freezeDossier(TransitionRequest request);

  @ActivityMethod(name = "HandoffTargetHearing")
  StageResult handoff(TransitionRequest request);

  @ActivityMethod(name = "CloseTargetHearing")
  StageResult close(TransitionRequest request);

  /** Immutable source authority and coordinates shared by every formalization request. */
  record TransitionRequest(
      HearingRoomStart start,
      HearingWorkflowStage expectedStage,
      int expectedStageSequence,
      long expectedProcessRevision,
      long expectedRoomRevision,
      long expectedFencingToken,
      String operationKey) {
    public TransitionRequest {
      start = Objects.requireNonNull(start, "start");
      expectedStage = Objects.requireNonNull(expectedStage, "expectedStage");
      if (expectedStageSequence != expectedStage.sequence()
          || expectedProcessRevision < 0
          || expectedRoomRevision < 0
          || expectedFencingToken != start.fencingToken()
          || operationKey == null
          || operationKey.isBlank()) {
        throw new IllegalArgumentException("target Hearing formalization request is invalid");
      }
    }
  }

  record PartyRequest(TransitionRequest transition, CaseCommandRef command) {
    public PartyRequest {
      transition = Objects.requireNonNull(transition, "transition");
      command = Objects.requireNonNull(command, "command");
      if (!transition.expectedStage().isPartyWait()
          || command.roomEpoch() != transition.start().roomEpoch()
          || !command.tenantSurrogate().equals(transition.start().tenantSurrogate())
          || !command.caseId().equals(transition.start().caseId())) {
        throw new IllegalArgumentException("target Hearing party formalization request is invalid");
      }
    }
  }

  record StageResult(HearingStageReceipt receipt) {
    public StageResult {
      receipt = Objects.requireNonNull(receipt, "receipt");
    }
  }

  record PartyResult(HearingPartyTerminalReceipt receipt) {
    public PartyResult {
      receipt = Objects.requireNonNull(receipt, "receipt");
    }
  }

  record AgentStagePreparation(com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest request) {
    public AgentStagePreparation {
      request = Objects.requireNonNull(request, "request");
    }
  }

  record AgentStageFinalizationRequest(
      TransitionRequest transition,
      com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result) {
    public AgentStageFinalizationRequest {
      transition = Objects.requireNonNull(transition, "transition");
      request = Objects.requireNonNull(request, "request");
      result = Objects.requireNonNull(result, "result");
    }
  }

  /**
   * The shared outer finalizer owns the AgentRun result and its cross-stage FINALIZE fact in one
   * transaction.  A room workflow relays only that finalizer receipt; replaying an intermediate
   * AGENT_RESULT receipt would create a second process-revision advance.
   */
  record AgentStageResult(HearingStageReceipt finalizerReceipt) {
    public AgentStageResult {
      finalizerReceipt = Objects.requireNonNull(finalizerReceipt, "finalizerReceipt");
    }
  }
}
