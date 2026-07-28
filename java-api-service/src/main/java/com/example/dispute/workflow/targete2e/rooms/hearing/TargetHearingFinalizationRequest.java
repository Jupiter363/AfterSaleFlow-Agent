package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.Objects;

/** Java-trusted Hearing formal command reconstructed from the immutable target proposal. */
public record TargetHearingFinalizationRequest(
    TargetHearingCommandMaterialStore.Snapshot material,
    FormalCommand formalCommand,
    String formalObjectId,
    String stageCode,
    long stageSequence,
    String actorId,
    ActorRole actorRole,
    Audience audience,
    CommitCommand command) {
  public TargetHearingFinalizationRequest {
    material = Objects.requireNonNull(material, "material");
    formalCommand = Objects.requireNonNull(formalCommand, "formalCommand");
    if (formalObjectId == null || formalObjectId.isBlank() || stageCode == null || stageCode.isBlank()
        || stageSequence < 0 || actorId == null || actorId.isBlank() || actorRole == null || audience == null) {
      throw new IllegalArgumentException("target Hearing finalization request is invalid");
    }
    command = Objects.requireNonNull(command, "command");
    var graph = command.request().command();
    var authority = formalCommand.authorityCommit().authority();
    if (!material.material().request().equals(command.request())
        || !authority.caseId().equals(graph.caseId())
        || authority.roomEpoch() != graph.roomEpoch()
        || authority.fencingToken() != material.admission().roomFencingToken()
        || !formalCommand.agentRunId().equals(command.request().agentRunId())
        || !formalCommand.agentResultHash().equals(command.result().resultHash())) {
      throw new IllegalArgumentException("target Hearing formal request does not bind its AgentRun");
    }
  }

  public sealed interface FormalCommand
      permits GeneratedAction, MatrixSynthesis, Decision {
    HearingAuthorityCommit authorityCommit();
    String agentRunId();
    String agentResultHash();
    HearingStageReceipt commit(HearingFormalReceiptService receipts);
  }

  public record GeneratedAction(HearingFormalFinalizer.ActionCommand value) implements FormalCommand {
    public GeneratedAction {
      value = Objects.requireNonNull(value, "value");
      if (value.actionType().isPartyAction()) {
        throw new IllegalArgumentException("target Graph cannot finalize a party action");
      }
    }
    @Override public HearingAuthorityCommit authorityCommit() { return value.authorityCommit(); }
    @Override public String agentRunId() { return value.agentRunId(); }
    @Override public String agentResultHash() { return value.agentResultHash(); }
    @Override public HearingStageReceipt commit(HearingFormalReceiptService receipts) {
      return receipts.appendGeneratedAction(value);
    }
  }

  public record MatrixSynthesis(HearingFormalFinalizer.MatrixSynthesisCommand value)
      implements FormalCommand {
    public MatrixSynthesis { value = Objects.requireNonNull(value, "value"); }
    @Override public HearingAuthorityCommit authorityCommit() { return value.authorityCommit(); }
    @Override public String agentRunId() { return value.agentRunId(); }
    @Override public String agentResultHash() { return value.agentResultHash(); }
    @Override public HearingStageReceipt commit(HearingFormalReceiptService receipts) {
      return receipts.finalizeMatrixSynthesis(value);
    }
  }

  public record Decision(HearingFormalFinalizer.DecisionCommand value) implements FormalCommand {
    public Decision { value = Objects.requireNonNull(value, "value"); }
    @Override public HearingAuthorityCommit authorityCommit() { return value.authorityCommit(); }
    @Override public String agentRunId() { return value.agentRunId(); }
    @Override public String agentResultHash() { return value.agentResultHash(); }
    @Override public HearingStageReceipt commit(HearingFormalReceiptService receipts) {
      return switch (value.artifactType()) {
        case JUDGE_PROPOSAL -> receipts.finalizeJudgeV1(value);
        case JURY_REVIEW_REPORT -> receipts.finalizeJuryReview(value);
        case ADJUDICATION_DRAFT -> receipts.finalizeJudgeV2(value);
      };
    }
  }
}
