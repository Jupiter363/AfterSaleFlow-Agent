package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Pure recovery planner. It can reuse receipts or checkpoints but cannot commit a business fact. */
public final class HearingRecoveryReconciler {

    public RecoveryPlan reconcile(ReconciliationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AuthoritySnapshot authority = request.authority();
        AttemptObservation attempt = request.attempt();

        RecoveryAction authorityAction = compareAuthority(authority, attempt);
        if (authorityAction != null) {
            return plan(authorityAction, null, null, authority, attempt);
        }

        CommittedReceipt receipt = attempt.committedReceipt();
        if (receipt != null) {
            if (!receipt.operationKeyHash().equals(attempt.operationKeyHash())
                    || receipt.sourceProcessRevision() != attempt.baseProcessRevision()
                    || receipt.processRevision() != Math.addExact(receipt.sourceProcessRevision(), 1)
                    || receipt.processRevision() != authority.processRevision()
                    || receipt.fencingToken() != authority.fencingToken()) {
                return plan(RecoveryAction.HALT_CONFLICT, null, null, authority, attempt);
            }
            if (attempt.acknowledgedReceiptHash() == null) {
                return plan(
                        RecoveryAction.RESIGNAL_COMMITTED_RECEIPT,
                        receipt.receiptHash(),
                        null,
                        authority,
                        attempt);
            }
            if (attempt.acknowledgedReceiptHash().equals(receipt.receiptHash())) {
                return plan(
                        RecoveryAction.CONSISTENT,
                        receipt.receiptHash(),
                        null,
                        authority,
                        attempt);
            }
            return plan(RecoveryAction.HALT_CONFLICT, null, null, authority, attempt);
        }

        if (attempt.acknowledgedReceiptHash() != null) {
            return plan(RecoveryAction.HALT_CONFLICT, null, null, authority, attempt);
        }
        if (attempt.baseProcessRevision() < authority.processRevision()) {
            return plan(RecoveryAction.REJECT_STALE_AUTHORITY, null, null, authority, attempt);
        }
        if (attempt.baseProcessRevision() > authority.processRevision()) {
            return plan(RecoveryAction.HALT_CONFLICT, null, null, authority, attempt);
        }
        if (attempt.graphCheckpointHash() != null) {
            return plan(
                    RecoveryAction.RESUME_GRAPH_CHECKPOINT,
                    null,
                    attempt.graphCheckpointHash(),
                    authority,
                    attempt);
        }
        return plan(RecoveryAction.RETRY_OPERATION, null, null, authority, attempt);
    }

    private static RecoveryAction compareAuthority(
            AuthoritySnapshot authority, AttemptObservation attempt) {
        if (attempt.roomEpoch() < authority.roomEpoch()
                || attempt.fencingToken() < authority.fencingToken()) {
            return RecoveryAction.REJECT_STALE_AUTHORITY;
        }
        if (attempt.roomEpoch() > authority.roomEpoch()
                || attempt.fencingToken() > authority.fencingToken()) {
            return RecoveryAction.HALT_CONFLICT;
        }
        return null;
    }

    private static RecoveryPlan plan(
            RecoveryAction action,
            String receiptHash,
            String checkpointHash,
            AuthoritySnapshot authority,
            AttemptObservation attempt) {
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("schema_version", "hearing-reconciliation-plan.v1");
        evidence.put("action", action.name());
        evidence.put("authority_hash", authority.authorityHash());
        evidence.put("attempt_hash", attempt.attemptHash());
        if (receiptHash != null) {
            evidence.put("receipt_hash", receiptHash);
        }
        if (checkpointHash != null) {
            evidence.put("checkpoint_hash", checkpointHash);
        }
        return new RecoveryPlan(
                action,
                receiptHash,
                checkpointHash,
                false,
                false,
                ContractJson.sha256Hex(evidence));
    }

    public record AuthoritySnapshot(
            long roomEpoch,
            long processRevision,
            long fencingToken,
            HearingWriterMode writerMode,
            int stageSequence,
            String stageHash) {

        public AuthoritySnapshot {
            if (roomEpoch < 1
                    || processRevision < 0
                    || fencingToken < 0
                    || stageSequence < 0
                    || stageSequence > 14) {
                throw new IllegalArgumentException("authority counters are outside Hearing bounds");
            }
            Objects.requireNonNull(writerMode, "writerMode must not be null");
            if (writerMode == HearingWriterMode.TEMPORAL && fencingToken < 1) {
                throw new IllegalArgumentException("TEMPORAL authority requires a positive fence");
            }
            requireSha256(stageHash, "stageHash");
        }

        public String authorityHash() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("room_epoch", roomEpoch);
            value.put("process_revision", processRevision);
            value.put("fencing_token", fencingToken);
            value.put("writer_mode", writerMode.name());
            value.put("stage_sequence", stageSequence);
            value.put("stage_hash", stageHash);
            return ContractJson.sha256Hex(value);
        }
    }

    public record AttemptObservation(
            long roomEpoch,
            long baseProcessRevision,
            long fencingToken,
            String operationKeyHash,
            String graphCheckpointHash,
            CommittedReceipt committedReceipt,
            String acknowledgedReceiptHash) {

        public AttemptObservation {
            if (roomEpoch < 1 || baseProcessRevision < 0 || fencingToken < 0) {
                throw new IllegalArgumentException("attempt authority values are invalid");
            }
            requireSha256(operationKeyHash, "operationKeyHash");
            requireOptionalSha256(graphCheckpointHash, "graphCheckpointHash");
            requireOptionalSha256(acknowledgedReceiptHash, "acknowledgedReceiptHash");
        }

        public String attemptHash() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("room_epoch", roomEpoch);
            value.put("base_process_revision", baseProcessRevision);
            value.put("fencing_token", fencingToken);
            value.put("operation_key_hash", operationKeyHash);
            if (graphCheckpointHash != null) {
                value.put("graph_checkpoint_hash", graphCheckpointHash);
            }
            if (committedReceipt != null) {
                value.put("committed_receipt_hash", committedReceipt.receiptHash());
            }
            if (acknowledgedReceiptHash != null) {
                value.put("acknowledged_receipt_hash", acknowledgedReceiptHash);
            }
            return ContractJson.sha256Hex(value);
        }
    }

    public record CommittedReceipt(
            String receiptHash,
            String operationKeyHash,
            long sourceProcessRevision,
            long processRevision,
            long fencingToken) {

        public CommittedReceipt {
            requireSha256(receiptHash, "receiptHash");
            requireSha256(operationKeyHash, "operationKeyHash");
            if (sourceProcessRevision < 0
                    || processRevision < 1
                    || fencingToken < 0) {
                throw new IllegalArgumentException("receipt authority values are invalid");
            }
        }
    }

    public record ReconciliationRequest(
            AuthoritySnapshot authority, AttemptObservation attempt) {

        public ReconciliationRequest {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(attempt, "attempt must not be null");
        }
    }

    public record RecoveryPlan(
            RecoveryAction action,
            String receiptHash,
            String checkpointHash,
            boolean formalWriteAllowed,
            boolean stageAdvanceAllowed,
            String planHash) {

        public RecoveryPlan {
            Objects.requireNonNull(action, "action must not be null");
            requireOptionalSha256(receiptHash, "receiptHash");
            requireOptionalSha256(checkpointHash, "checkpointHash");
            requireSha256(planHash, "planHash");
            if (formalWriteAllowed || stageAdvanceAllowed) {
                throw new IllegalArgumentException(
                        "reconciliation planning cannot write or advance Hearing");
            }
            if (action == RecoveryAction.RESIGNAL_COMMITTED_RECEIPT && receiptHash == null) {
                throw new IllegalArgumentException("receipt re-signal requires a committed receipt");
            }
            if (action == RecoveryAction.RESUME_GRAPH_CHECKPOINT && checkpointHash == null) {
                throw new IllegalArgumentException("Graph resume requires a checkpoint");
            }
        }
    }

    public enum RecoveryAction {
        CONSISTENT,
        RETRY_OPERATION,
        RESUME_GRAPH_CHECKPOINT,
        RESIGNAL_COMMITTED_RECEIPT,
        REJECT_STALE_AUTHORITY,
        HALT_CONFLICT
    }

    private static void requireOptionalSha256(String value, String field) {
        if (value != null) {
            requireSha256(value, field);
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
