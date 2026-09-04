package com.example.dispute.workflow.runtime.finalization;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Caller-transaction-owned append/replay boundary for immutable target receipts. */
public interface ProductionFinalizationReceiptLedger {

    Optional<StoredReceipt> find(String activationId, String logicalRunId);

    StoredReceipt append(AppendCommand command);

    static void requireExact(StoredReceipt persisted, AppendCommand expected) {
        byte[] expectedBytes = ProductionFinalizationReceiptCodec.canonicalBytes(expected.receipt());
        if (!persisted.activationManifestHash().equals(expected.activationManifestHash())
                || !persisted.receipt().equals(expected.receipt())
                || !Arrays.equals(persisted.canonicalBytes(), expectedBytes)) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_FINALIZATION_RECEIPT_CONFLICT",
                    "logical run is already finalized with different receipt evidence");
        }
    }

    record AppendCommand(
            String activationManifestHash, ProductionFinalizationReceipt receipt) {
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
            ProductionFinalizationReceipt receipt,
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
