package com.example.dispute.executor.application;

import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.time.Instant;
import java.util.Objects;

/** Formal target-lane adapter over the shared Java-authoritative Outcome ledger. */
public final class TargetTemporalOutcomeLedgerAdapter {
  private final OutcomeOperationLedger ledger;

  public TargetTemporalOutcomeLedgerAdapter(OutcomeOperationLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  public OutcomeProcessProjection createProjection(OutcomeProcessProjection projection) {
    requireTemporal(projection);
    return ledger.createProjection(projection);
  }

  public OutcomeProcessProjection advance(OutcomeOperationLedger.ProjectionExpectation expectation,
      OutcomeProcessProjection.ProcessState nextState, Instant advancedAt) {
    return ledger.advanceProjection(expectation, nextState, advancedAt);
  }

  public OutcomeOperation reserve(OutcomeOperationCommand command, Binding binding, Instant reservedAt) {
    Objects.requireNonNull(command, "command"); Objects.requireNonNull(binding, "binding");
    if (command.runtimeMode() != OutcomeWireTypes.RuntimeMode.TEMPORAL || command.syntheticOnly()
        || command.effectClass() != OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT
        || !binding.projection().caseId().equals(command.caseId())
        || binding.projection().outcomeEpoch() != command.epoch()
        || binding.projection().fencingToken() != command.fence()) {
      throw new IllegalArgumentException("formal target Outcome command is not ledger-authorized");
    }
    return ledger.reserve(new OutcomeOperation(command.operationId(), binding.projection().projectionId(),
        binding.projection().tenantSurrogate(), command.caseId(), command.epoch(), command.fence(),
        binding.projection().processRevision(), binding.projection().outcomeRevision(),
        OutcomeOperation.OperationKind.OPERATION, command.operationSequence(), command.operationKeyHash(),
        command.requestHash(), binding.reviewPacketId(), binding.reviewPacketVersion(), binding.reviewPacketHash(),
        binding.reviewPacketActionHash(), binding.approvalRecordId(), binding.approvalActionHash(),
        binding.decisionRequestHash(), binding.policyVersion(), binding.actionRecordId(command.operationSequence()),
        command.approvedActionSnapshotHash(), "TARGET_E2E_MANIFEST_NOOP", command.toolCapabilityVersion(),
        OutcomeOperation.RetryClass.NON_RETRYABLE, command.externalIdempotencyKeyHash(), true, false, reservedAt), null);
  }

  public OutcomeOperationReceipt recordNoEffectSuccess(
      OutcomeOperationCommand command, String receiptId, String receiptHash, String resultRef,
      String resultHash, Instant completedAt, Binding binding) {
    return ledger.recordReceipt(new OutcomeOperationReceipt(receiptId, receiptHash, command.operationId(),
        binding.projection().tenantSurrogate(), command.caseId(), command.epoch(), command.fence(),
        command.requestHash(), OutcomeOperationReceipt.ReceiptStatus.SUCCEEDED,
        OutcomeOperationReceipt.ReceiptAuthority.JAVA_RECONCILIATION,
        "target-noop:" + receiptId, resultRef, resultHash,
        OutcomeOperationReceipt.ClosureDisposition.SATISFIED, completedAt));
  }

  private static void requireTemporal(OutcomeProcessProjection projection) {
    if (projection.writerMode() != OutcomeProcessProjection.WriterMode.TEMPORAL
        || projection.runtimeMode() != OutcomeProcessProjection.RuntimeMode.TEMPORAL) {
      throw new IllegalArgumentException("target Outcome projection must be TEMPORAL");
    }
  }

  public record Binding(OutcomeProcessProjection projection, String reviewPacketId,
      int reviewPacketVersion, String reviewPacketHash, String reviewPacketActionHash,
      String approvalRecordId, String approvalActionHash, String decisionRequestHash,
      String policyVersion, java.util.List<String> actionRecordIds) {
    public Binding {
      projection = Objects.requireNonNull(projection, "projection");
      actionRecordIds = java.util.List.copyOf(actionRecordIds);
      if (actionRecordIds.size() != projection.expectedRequiredOperationCount()) {
        throw new IllegalArgumentException("target Outcome action record set is not exact");
      }
    }
    public String actionRecordId(long sequence) {
      if (sequence < 1 || sequence > actionRecordIds.size()) throw new IllegalArgumentException("invalid operation sequence");
      return actionRecordIds.get(Math.toIntExact(sequence - 1));
    }
  }
}
