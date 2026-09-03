package com.example.dispute.agentstream.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Formal-finalizer terminal binding plus optional durable delivery-archive evidence. This type can
 * identify a release cleanup candidate; it never authorizes detach, drop, or business completion.
 */
public final class AgentRunStreamRetentionManifest {

    public static final Duration MINIMUM_HOT_RETENTION = Duration.ofHours(24);

    private final String runId;
    private final String attemptId;
    private final long terminalSequenceNo;
    private final String terminalPayloadHash;
    private final String agentExecutionManifestId;
    private final String agentExecutionManifestHash;
    private final Instant finalizedAt;
    private final Instant hotRetainUntil;
    private final ArchiveReceipt archiveReceipt;

    /**
     * Compatibility constructor used by the existing PostgreSQL finalizer projection. Boolean
     * verification is deliberately rejected; only {@link #withArchiveReceipt(ArchiveReceipt)} can
     * add verified retention evidence.
     */
    AgentRunStreamRetentionManifest(
            String runId,
            String attemptId,
            long terminalSequenceNo,
            String terminalPayloadHash,
            String agentExecutionManifestId,
            String agentExecutionManifestHash,
            Instant finalizedAt,
            Instant hotRetainUntil,
            boolean compactionVerified,
            boolean archiveVerified) {
        this(
                runId,
                attemptId,
                terminalSequenceNo,
                terminalPayloadHash,
                agentExecutionManifestId,
                agentExecutionManifestHash,
                finalizedAt,
                hotRetainUntil,
                rejectBooleanVerification(compactionVerified, archiveVerified));
    }

    private AgentRunStreamRetentionManifest(
            String runId,
            String attemptId,
            long terminalSequenceNo,
            String terminalPayloadHash,
            String agentExecutionManifestId,
            String agentExecutionManifestHash,
            Instant finalizedAt,
            Instant hotRetainUntil,
            ArchiveReceipt archiveReceipt) {
        this.runId = required(runId, "runId");
        this.attemptId = required(attemptId, "attemptId");
        if (terminalSequenceNo < 0) {
            throw new IllegalArgumentException("terminalSequenceNo must not be negative");
        }
        this.terminalSequenceNo = terminalSequenceNo;
        this.terminalPayloadHash = sha256(terminalPayloadHash, "terminalPayloadHash");
        this.agentExecutionManifestId =
                required(agentExecutionManifestId, "agentExecutionManifestId");
        this.agentExecutionManifestHash =
                sha256(agentExecutionManifestHash, "agentExecutionManifestHash");
        this.finalizedAt = Objects.requireNonNull(finalizedAt, "finalizedAt must not be null");
        this.hotRetainUntil =
                Objects.requireNonNull(hotRetainUntil, "hotRetainUntil must not be null");
        if (hotRetainUntil.isBefore(finalizedAt.plus(MINIMUM_HOT_RETENTION))) {
            throw new IllegalArgumentException("hot stream retention must be at least 24 hours");
        }
        this.archiveReceipt = archiveReceipt;
        if (archiveReceipt != null) {
            requireReceiptBinding(archiveReceipt);
        }
    }

    AgentRunStreamRetentionManifest withArchiveReceipt(ArchiveReceipt receipt) {
        return new AgentRunStreamRetentionManifest(
                runId,
                attemptId,
                terminalSequenceNo,
                terminalPayloadHash,
                agentExecutionManifestId,
                agentExecutionManifestHash,
                finalizedAt,
                hotRetainUntil,
                Objects.requireNonNull(receipt, "receipt"));
    }

    /**
     * STREAM-013 engineering eligibility only; a separate authorized release still owns cleanup.
     */
    public boolean releaseCleanupEligible(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return archiveReceipt != null
                && !now.isBefore(hotRetainUntil)
                && !now.isBefore(archiveReceipt.hotRetentionEligibleAt())
                && archiveReceipt.verifiedReadback()
                && archiveReceipt.terminalEventRetained()
                && archiveReceipt.immutableManifest()
                && archiveReceipt.deliveryHighWatermark() >= terminalSequenceNo
                && !archiveReceipt.formalBusinessAuthority()
                && !archiveReceipt.releaseEvidenceComplete();
    }

    private void requireReceiptBinding(ArchiveReceipt receipt) {
        if (!runId.equals(receipt.runId())
                || !attemptId.equals(receipt.attemptId())
                || terminalSequenceNo != receipt.lastSequenceNo()
                || receipt.firstSequenceNo() != 0
                || receipt.eventCount() != terminalSequenceNo + 1
                || !terminalPayloadHash.equals(receipt.terminalPayloadHash())
                || !agentExecutionManifestId.equals(receipt.agentExecutionManifestId())
                || !agentExecutionManifestHash.equals(receipt.agentExecutionManifestHash())
                || !receipt.hotRetentionStartedAt().equals(finalizedAt)
                || receipt.hotRetentionEligibleAt()
                        .isBefore(finalizedAt.plus(MINIMUM_HOT_RETENTION))) {
            throw new IllegalArgumentException(
                    "archive receipt does not bind the formal terminal retention manifest");
        }
        receipt.requireVerifiedDeliveryEvidence();
    }

    public String runId() {
        return runId;
    }

    public String attemptId() {
        return attemptId;
    }

    public long terminalSequenceNo() {
        return terminalSequenceNo;
    }

    public String terminalPayloadHash() {
        return terminalPayloadHash;
    }

    public String agentExecutionManifestId() {
        return agentExecutionManifestId;
    }

    public String agentExecutionManifestHash() {
        return agentExecutionManifestHash;
    }

    public Instant finalizedAt() {
        return finalizedAt;
    }

    public Instant hotRetainUntil() {
        return hotRetainUntil;
    }

    public ArchiveReceipt archiveReceipt() {
        return archiveReceipt;
    }

    public boolean archiveVerified() {
        return archiveReceipt != null && archiveReceipt.verifiedReadback();
    }

    public boolean compactionVerified() {
        return false;
    }

    public static final class ArchiveReceipt {
        private final String receiptId;
        private final String receiptHash;
        private final String manifestId;
        private final String manifestHash;
        private final String targetPartitionName;
        private final String runId;
        private final String attemptId;
        private final long firstSequenceNo;
        private final long lastSequenceNo;
        private final long eventCount;
        private final String canonicalEventsHash;
        private final String objectVersion;
        private final String objectHash;
        private final String objectReadbackHash;
        private final String objectCreationReceiptId;
        private final String objectCreationReceiptHash;
        private final long deliveryHighWatermark;
        private final Instant hotRetentionStartedAt;
        private final Instant hotRetentionEligibleAt;
        private final String terminalPayloadHash;
        private final String agentExecutionManifestId;
        private final String agentExecutionManifestHash;
        private final boolean terminalEventRetained;
        private final boolean immutableManifest;
        private final boolean formalBusinessAuthority;
        private final boolean releaseEvidenceComplete;

        ArchiveReceipt(
                String receiptId,
                String receiptHash,
                String manifestId,
                String manifestHash,
                String targetPartitionName,
                String runId,
                String attemptId,
                long firstSequenceNo,
                long lastSequenceNo,
                long eventCount,
                String canonicalEventsHash,
                String objectVersion,
                String objectHash,
                String objectReadbackHash,
                String objectCreationReceiptId,
                String objectCreationReceiptHash,
                long deliveryHighWatermark,
                Instant hotRetentionStartedAt,
                Instant hotRetentionEligibleAt,
                String terminalPayloadHash,
                String agentExecutionManifestId,
                String agentExecutionManifestHash,
                boolean terminalEventRetained,
                boolean immutableManifest,
                boolean formalBusinessAuthority,
                boolean releaseEvidenceComplete) {
            this.receiptId = required(receiptId, "receiptId");
            this.receiptHash = sha256(receiptHash, "receiptHash");
            this.manifestId = required(manifestId, "manifestId");
            this.manifestHash = sha256(manifestHash, "manifestHash");
            this.targetPartitionName = required(targetPartitionName, "targetPartitionName");
            this.runId = required(runId, "runId");
            this.attemptId = required(attemptId, "attemptId");
            this.firstSequenceNo = firstSequenceNo;
            this.lastSequenceNo = lastSequenceNo;
            this.eventCount = eventCount;
            this.canonicalEventsHash = sha256(canonicalEventsHash, "canonicalEventsHash");
            this.objectVersion = required(objectVersion, "objectVersion");
            this.objectHash = sha256(objectHash, "objectHash");
            this.objectReadbackHash = sha256(objectReadbackHash, "objectReadbackHash");
            this.objectCreationReceiptId =
                    required(objectCreationReceiptId, "objectCreationReceiptId");
            this.objectCreationReceiptHash =
                    sha256(objectCreationReceiptHash, "objectCreationReceiptHash");
            this.deliveryHighWatermark = deliveryHighWatermark;
            this.hotRetentionStartedAt = Objects.requireNonNull(
                    hotRetentionStartedAt, "hotRetentionStartedAt");
            this.hotRetentionEligibleAt = Objects.requireNonNull(
                    hotRetentionEligibleAt, "hotRetentionEligibleAt");
            this.terminalPayloadHash = sha256(terminalPayloadHash, "terminalPayloadHash");
            this.agentExecutionManifestId =
                    required(agentExecutionManifestId, "agentExecutionManifestId");
            this.agentExecutionManifestHash =
                    sha256(agentExecutionManifestHash, "agentExecutionManifestHash");
            this.terminalEventRetained = terminalEventRetained;
            this.immutableManifest = immutableManifest;
            this.formalBusinessAuthority = formalBusinessAuthority;
            this.releaseEvidenceComplete = releaseEvidenceComplete;
        }

        ArchiveReceipt requireVerifiedDeliveryEvidence() {
            if (firstSequenceNo < 0
                    || lastSequenceNo < firstSequenceNo
                    || eventCount != lastSequenceNo - firstSequenceNo + 1
                    || deliveryHighWatermark < lastSequenceNo
                    || hotRetentionEligibleAt.isBefore(
                            hotRetentionStartedAt.plus(MINIMUM_HOT_RETENTION))
                    || !objectHash.equals(objectReadbackHash)
                    || !terminalEventRetained
                    || !immutableManifest
                    || formalBusinessAuthority
                    || releaseEvidenceComplete) {
                throw new IllegalArgumentException(
                        "archive receipt is not verified delivery-only STREAM-013 evidence");
            }
            return this;
        }

        public boolean verifiedReadback() {
            return objectHash.equals(objectReadbackHash);
        }

        public String receiptId() {
            return receiptId;
        }

        public String receiptHash() {
            return receiptHash;
        }

        public String manifestId() {
            return manifestId;
        }

        public String manifestHash() {
            return manifestHash;
        }

        public String targetPartitionName() {
            return targetPartitionName;
        }

        public String runId() {
            return runId;
        }

        public String attemptId() {
            return attemptId;
        }

        public long firstSequenceNo() {
            return firstSequenceNo;
        }

        public long lastSequenceNo() {
            return lastSequenceNo;
        }

        public long eventCount() {
            return eventCount;
        }

        public String canonicalEventsHash() {
            return canonicalEventsHash;
        }

        public String objectVersion() {
            return objectVersion;
        }

        public String objectHash() {
            return objectHash;
        }

        public String objectReadbackHash() {
            return objectReadbackHash;
        }

        public String objectCreationReceiptId() {
            return objectCreationReceiptId;
        }

        public String objectCreationReceiptHash() {
            return objectCreationReceiptHash;
        }

        public long deliveryHighWatermark() {
            return deliveryHighWatermark;
        }

        public Instant hotRetentionStartedAt() {
            return hotRetentionStartedAt;
        }

        public Instant hotRetentionEligibleAt() {
            return hotRetentionEligibleAt;
        }

        public String terminalPayloadHash() {
            return terminalPayloadHash;
        }

        public String agentExecutionManifestId() {
            return agentExecutionManifestId;
        }

        public String agentExecutionManifestHash() {
            return agentExecutionManifestHash;
        }

        public boolean terminalEventRetained() {
            return terminalEventRetained;
        }

        public boolean immutableManifest() {
            return immutableManifest;
        }

        public boolean formalBusinessAuthority() {
            return formalBusinessAuthority;
        }

        public boolean releaseEvidenceComplete() {
            return releaseEvidenceComplete;
        }
    }

    private static ArchiveReceipt rejectBooleanVerification(
            boolean compactionVerified, boolean archiveVerified) {
        if (compactionVerified || archiveVerified) {
            throw new IllegalArgumentException(
                    "verification requires a durable immutable archive receipt");
        }
        return null;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }
}
