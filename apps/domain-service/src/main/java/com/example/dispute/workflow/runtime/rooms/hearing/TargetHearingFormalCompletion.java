package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import java.util.Objects;

/**
 * Target-lane formal-domain seam.
 *
 * <p>This type deliberately owns only the Java Hearing fact. The multi-room outer finalizer owns
 * target receipt persistence and activation-command completion, after the formal receipt has been
 * returned. Keeping those writes out of this class prevents a room-local finalizer from completing
 * an admission before the shared finalization transaction has durably recorded its receipt.
 */
public final class TargetHearingFormalCompletion {
  private final HearingFormalReceiptService receipts;

  public TargetHearingFormalCompletion(HearingFormalReceiptService receipts) {
    this.receipts = Objects.requireNonNull(receipts, "receipts");
  }

  public HearingStageReceipt appendGenerated(HearingFormalFinalizer.ActionCommand command) {
    requireTarget(command);
    return receipts.appendGeneratedAction(command);
  }

  public HearingStageReceipt advanceStage(HearingFormalFinalizer.StageCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return receipts.advanceStage(command);
  }

  public HearingStageReceipt freezeDossier(HearingFormalFinalizer.DossierCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return receipts.freezeDossier(command);
  }

  public HearingStageReceipt commitHandoff(HearingFormalFinalizer.HandoffCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return receipts.commitHandoff(command);
  }

  public HearingStageReceipt commitClosure(HearingFormalFinalizer.ClosureCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return receipts.commitClosure(command);
  }

  public HearingStageReceipt commit(TargetHearingFinalizationRequest.FormalCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return command.commit(receipts);
  }

  public HearingPartyTerminalReceipt appendParty(HearingFormalFinalizer.ActionCommand command) {
    requireTarget(command);
    return receipts.appendPartyAction(command);
  }

  public HearingPartyTerminalReceipt adoptParty(
      HearingFormalFinalizer.AdoptPartyActionCommand command) {
    Objects.requireNonNull(command, "command");
    requireTarget(command.authorityCommit());
    return receipts.adoptPartyAction(command);
  }

  private static void requireTarget(HearingFormalFinalizer.ActionCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.authorityCommit().authority().writerMode() != HearingWriterMode.TEMPORAL
        || command.authorityCommit().authority().fencingToken() < 1) {
      throw new IllegalArgumentException("Hearing formal completion requires a fenced TEMPORAL authority");
    }
  }

  private static void requireTarget(com.example.dispute.hearing.domain.HearingAuthorityCommit command) {
    Objects.requireNonNull(command, "command");
    if (command.authority().writerMode() != HearingWriterMode.TEMPORAL
        || command.authority().fencingToken() < 1) {
      throw new IllegalArgumentException("Hearing formal completion requires a fenced TEMPORAL authority");
    }
  }
}
