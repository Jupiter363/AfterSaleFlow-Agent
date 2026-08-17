package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.evidence.application.EvidenceContentAuthorityV1;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Append-only stored form of a successful parser result. */
@Entity
@Table(name = "evidence_content_authority")
public class EvidenceContentAuthorityEntity extends AbstractEntity {

    @Column(name = "parse_outbox_id", length = 64, nullable = false, updatable = false)
    private String parseOutboxId;
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
    @Column(name = "parsed_content_sha256", length = 64, nullable = false, updatable = false)
    private String parsedContentSha256;
    @Column(name = "parsed_text", nullable = false, updatable = false, columnDefinition = "text")
    private String parsedText;
    @Column(name = "parsed_byte_length", nullable = false, updatable = false)
    private long parsedByteLength;
    @Column(name = "completed_at", nullable = false, updatable = false)
    private OffsetDateTime completedAt;
    @Column(name = "status", length = 32, nullable = false, updatable = false)
    private String status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EvidenceContentAuthorityEntity() {}

    private EvidenceContentAuthorityEntity(String id) { super(id); }

    public static EvidenceContentAuthorityEntity from(
            String id,
            String parseOutboxId,
            EvidenceContentAuthorityV1 authority,
            long fileSize,
            String sourceBucket,
            String sourceObjectKey,
            OffsetDateTime createdAt) {
        EvidenceContentAuthorityEntity entity = new EvidenceContentAuthorityEntity(id);
        entity.parseOutboxId = Objects.requireNonNull(parseOutboxId, "parseOutboxId");
        entity.caseId = authority.caseId();
        entity.evidenceId = authority.evidenceId();
        entity.fileSha256 = authority.fileSha256();
        entity.contentType = authority.contentType();
        if (fileSize < 1 || fileSize > 25L * 1024 * 1024) {
            throw new IllegalArgumentException("content authority fileSize is invalid");
        }
        entity.fileSize = fileSize;
        entity.parserVersion = authority.parserVersion();
        entity.sourceBucket = Objects.requireNonNull(sourceBucket, "sourceBucket");
        entity.sourceObjectKey = Objects.requireNonNull(sourceObjectKey, "sourceObjectKey");
        entity.parsedContentSha256 = authority.parsedContentSha256();
        entity.parsedText = authority.parsedText();
        entity.parsedByteLength = authority.parsedByteLength();
        entity.completedAt = OffsetDateTime.parse(authority.completedAt());
        entity.status = authority.status();
        entity.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return entity;
    }

    public EvidenceContentAuthorityV1 authority() {
        return new EvidenceContentAuthorityV1(
                EvidenceContentAuthorityV1.SCHEMA_VERSION,
                caseId,
                evidenceId,
                fileSha256,
                contentType,
                parserVersion,
                parsedContentSha256,
                parsedText,
                parsedByteLength,
                completedAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).toString(),
                status);
    }

    public String getParseOutboxId() { return parseOutboxId; }
    public String getCaseId() { return caseId; }
    public String getEvidenceId() { return evidenceId; }
    public String getFileSha256() { return fileSha256; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getParserVersion() { return parserVersion; }
    public String getParsedContentSha256() { return parsedContentSha256; }
    public String getParsedText() { return parsedText; }
    public long getParsedByteLength() { return parsedByteLength; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public String getStatus() { return status; }
}
