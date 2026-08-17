package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.evidence.domain.EvidenceParseOutboxStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;

/** One durable logical parser request, bound to one immutable Evidence file coordinate. */
@Entity
@Table(name = "evidence_parse_outbox")
public class EvidenceParseOutboxEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "evidence_id", length = 64, nullable = false, updatable = false)
    private String evidenceId;

    @Column(name = "file_sha256", length = 64, nullable = false, updatable = false)
    private String fileSha256;

    @Column(name = "content_type", length = 128, nullable = false, updatable = false)
    private String contentType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "parser_version", length = 128, nullable = false, updatable = false)
    private String parserVersion;

    @Column(name = "source_bucket", length = 128, nullable = false, updatable = false)
    private String sourceBucket;

    @Column(name = "source_object_key", length = 512, nullable = false, updatable = false)
    private String sourceObjectKey;

    @Column(name = "request_key", length = 128, nullable = false, updatable = false)
    private String requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", length = 32, nullable = false)
    private EvidenceParseOutboxStatus status;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_detail", columnDefinition = "text")
    private String lastErrorDetail;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected EvidenceParseOutboxEntity() {}

    private EvidenceParseOutboxEntity(String id) {
        super(id);
    }

    public static EvidenceParseOutboxEntity pending(
            String id,
            String caseId,
            String evidenceId,
            String fileSha256,
            String contentType,
            long fileSize,
            String parserVersion,
            String sourceBucket,
            String sourceObjectKey,
            String requestKey,
            OffsetDateTime now) {
        EvidenceParseOutboxEntity entity = new EvidenceParseOutboxEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.evidenceId = required(evidenceId, "evidenceId");
        entity.fileSha256 = requiredHash(fileSha256, "fileSha256");
        entity.contentType = required(contentType, "contentType");
        if (fileSize < 1 || fileSize > 25L * 1024 * 1024) {
            throw new IllegalArgumentException("fileSize is invalid");
        }
        entity.fileSize = fileSize;
        entity.parserVersion = required(parserVersion, "parserVersion");
        entity.sourceBucket = required(sourceBucket, "sourceBucket");
        entity.sourceObjectKey = required(sourceObjectKey, "sourceObjectKey");
        entity.requestKey = requiredHash(requestKey, "requestKey");
        entity.status = EvidenceParseOutboxStatus.PENDING;
        entity.availableAt = Objects.requireNonNull(now, "now");
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void claim(String leaseOwner, OffsetDateTime now, OffsetDateTime leaseExpiresAt) {
        if (!claimableAt(now) || leaseOwner == null || leaseOwner.isBlank() || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalStateException("evidence parse outbox is not claimable");
        }
        status = EvidenceParseOutboxStatus.IN_FLIGHT;
        attemptCount = Math.incrementExact(attemptCount);
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        updatedAt = now;
    }

    public boolean claimableAt(OffsetDateTime now) {
        return (status == EvidenceParseOutboxStatus.PENDING && !availableAt.isAfter(now))
                || (status == EvidenceParseOutboxStatus.IN_FLIGHT
                        && leaseExpiresAt != null
                        && !leaseExpiresAt.isAfter(now));
    }

    public void defer(String leaseOwner, String errorCode, String errorDetail, OffsetDateTime availableAt, OffsetDateTime now) {
        requireLease(leaseOwner, now);
        status = EvidenceParseOutboxStatus.PENDING;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt");
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.lastErrorCode = truncate(errorCode, 64);
        this.lastErrorDetail = truncate(errorDetail, 4096);
        updatedAt = now;
    }

    public void markApplied(String leaseOwner, OffsetDateTime now) {
        requireLease(leaseOwner, now);
        status = EvidenceParseOutboxStatus.APPLIED;
        appliedAt = now;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        lastErrorCode = null;
        lastErrorDetail = null;
        updatedAt = now;
    }

    public void markFailed(String leaseOwner, String errorCode, String errorDetail, OffsetDateTime now) {
        requireLease(leaseOwner, now);
        status = EvidenceParseOutboxStatus.FAILED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        lastErrorCode = truncate(errorCode, 64);
        lastErrorDetail = truncate(errorDetail, 4096);
        updatedAt = now;
    }

    private void requireLease(String leaseOwner, OffsetDateTime now) {
        if (status != EvidenceParseOutboxStatus.IN_FLIGHT
                || !Objects.equals(this.leaseOwner, leaseOwner)
                || leaseExpiresAt == null
                || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalStateException("evidence parse outbox lease is stale");
        }
    }

    public String getCaseId() { return caseId; }
    public String getEvidenceId() { return evidenceId; }
    public String getFileSha256() { return fileSha256; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getParserVersion() { return parserVersion; }
    public String getSourceBucket() { return sourceBucket; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public String getRequestKey() { return requestKey; }
    public EvidenceParseOutboxStatus getStatus() { return status; }
    public OffsetDateTime getAvailableAt() { return availableAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getLeaseOwner() { return leaseOwner; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requiredHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String truncate(String value, int limit) {
        String safe = value == null || value.isBlank() ? "UNKNOWN" : value;
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }
}
