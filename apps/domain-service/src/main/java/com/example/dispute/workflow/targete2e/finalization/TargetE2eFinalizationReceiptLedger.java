package com.example.dispute.workflow.targete2e.finalization;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Caller-transaction-owned append/replay boundary for immutable target receipts. */
public interface TargetE2eFinalizationReceiptLedger {

    Optional<StoredReceipt> find(String activationId, String logicalRunId);

    StoredReceipt append(AppendCommand command);

    static void requireExact(StoredReceipt persisted, AppendCommand expected) {
        byte[] expectedBytes = TargetE2eFinalizationReceiptCodec.canonicalBytes(expected.receipt());
        if (!persisted.activationManifestHash().equals(expected.activationManifestHash())
                || !persisted.receipt().equals(expected.receipt())
                || !Arrays.equals(persisted.canonicalBytes(), expectedBytes)) {
            throw new TargetE2eFinalizationRejectedException(
                    "TARGET_E2E_FINALIZATION_RECEIPT_CONFLICT",
                    "logical run is already finalized with different receipt evidence");
        }
    }

    record AppendCommand(
            String activationManifestHash, TargetE2eFinalizationReceipt receipt) {
        public AppendCommand {
            if (activationManifestHash == null
                    || !activationManifestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "activationManifestHash must be a lowercase SHA-256");
            }
            receipt = Objects.requireNonNull(receipt, "receipt");
            receipt.requireCanonicalHash();
        }
    }

    record StoredReceipt(
            String receiptId,
            String activationManifestHash,
            TargetE2eFinalizationReceipt receipt,
            byte[] canonicalBytes) {
        public StoredReceipt {
            if (receiptId == null || receiptId.isBlank()) {
                throw new IllegalArgumentException("receiptId is required");
            }
            if (activationManifestHash == null
                    || !activationManifestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("activationManifestHash is invalid");
            }
            receipt = Objects.requireNonNull(receipt, "receipt");
            canonicalBytes = Arrays.copyOf(
                    Objects.requireNonNull(canonicalBytes, "canonicalBytes"),
                    canonicalBytes.length);
        }

        @Override
        public byte[] canonicalBytes() {
            return Arrays.copyOf(canonicalBytes, canonicalBytes.length);
        }
    }
}
