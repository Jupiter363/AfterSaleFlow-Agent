package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "immutable_payload_snapshot")
public class ImmutablePayloadSnapshotEntity extends AbstractEntity {

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, updatable = false)
    private RoomType roomType;

    @Column(name = "snapshot_type", length = 64, nullable = false, updatable = false)
    private String snapshotType;

    @Column(name = "source_type", length = 64, nullable = false, updatable = false)
    private String sourceType;

    @Column(name = "source_id", length = 128, nullable = false, updatable = false)
    private String sourceId;

    @Column(name = "schema_version", length = 128, nullable = false, updatable = false)
    private String schemaVersion;

    @Column(name = "object_uri", length = 1024, nullable = false, updatable = false)
    private String objectUri;

    @Column(name = "object_version", length = 128, updatable = false)
    private String objectVersion;

    @Column(name = "content_sha256", length = 64, nullable = false, updatable = false)
    private String contentSha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content_type", length = 128, updatable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 32, nullable = false, updatable = false)
    private Visibility visibility;

    @Column(name = "encryption_key_ref", length = 256, updatable = false)
    private String encryptionKeyRef;

    @Column(name = "legal_hold", nullable = false, updatable = false)
    private boolean legalHold;

    @Column(name = "retained_until", updatable = false)
    private OffsetDateTime retainedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected ImmutablePayloadSnapshotEntity() {}

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getObjectUri() {
        return objectUri;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public Visibility getVisibility() {
        return visibility;
    }
}
