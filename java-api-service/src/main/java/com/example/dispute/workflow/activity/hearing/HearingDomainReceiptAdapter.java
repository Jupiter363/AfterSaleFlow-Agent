package com.example.dispute.workflow.activity.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import java.util.Objects;

/** Pure adapter from the Java domain ledger to bounded Workflow Signal payloads. */
public final class HearingDomainReceiptAdapter {

  private HearingDomainReceiptAdapter() {}

  public static HearingStageReceipt stage(HearingDomainReceipt receipt) {
    HearingCommittedReceipt committed = committed(receipt);
    if (committed.operationType() == HearingAuthorityCommit.OperationType.PARTY_TERMINAL) {
      throw new IllegalArgumentException("party terminal receipt requires party metadata");
    }
    return new HearingStageReceipt(HearingStageReceipt.SCHEMA_VERSION, committed);
  }

  public static HearingPartyTerminalReceipt party(
      HearingDomainReceipt receipt,
      String requestId,
      String participantId,
      HearingPartyTerminalReceipt.TerminalStatus terminalStatus) {
    HearingCommittedReceipt committed = committed(receipt);
    return new HearingPartyTerminalReceipt(
        HearingPartyTerminalReceipt.SCHEMA_VERSION,
        requestId,
        participantId,
        terminalStatus,
        committed);
  }

  public static HearingCommittedReceipt committed(HearingDomainReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt must not be null");
    return new HearingCommittedReceipt(
        HearingCommittedReceipt.SCHEMA_VERSION,
        receipt.receiptId(),
        receipt.receiptHash(),
        receipt.operationType(),
        receipt.operationKey(),
        receipt.requestHash(),
        receipt.tenantSurrogate(),
        receipt.caseId(),
        receipt.flowInstanceId(),
        receipt.epochId(),
        receipt.roomEpoch(),
        receipt.writerMode(),
        receipt.fencingToken(),
        HearingWorkflowStage.valueOf(receipt.sourceStage().name()),
        receipt.sourceStageSequence(),
        receipt.sourceProcessRevision(),
        receipt.sourceRoomRevision(),
        HearingWorkflowStage.valueOf(receipt.stage().name()),
        receipt.stageSequence(),
        receipt.sharedDeadlineAt(),
        receipt.processRevision(),
        receipt.roomRevision(),
        receipt.resultRef(),
        receipt.resultHash(),
        receipt.committedEventSequence(),
        receipt.temporalHistoryEventId());
  }
}
