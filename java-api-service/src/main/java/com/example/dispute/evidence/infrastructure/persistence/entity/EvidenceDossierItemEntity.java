package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "evidence_dossier_item")
public class EvidenceDossierItemEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "dossier_id", length = 64, nullable = false)
    private String dossierId;

    @Column(name = "evidence_id", length = 64, nullable = false)
    private String evidenceId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceSnapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected EvidenceDossierItemEntity() {}

    private EvidenceDossierItemEntity(String id) {
        super(id);
    }

    public static EvidenceDossierItemEntity snapshot(
            String id,
            String caseId,
            String dossierId,
            String evidenceId,
            int sequenceNo,
            String evidenceSnapshotJson,
            Instant now,
            String actorId) {
        EvidenceDossierItemEntity entity = new EvidenceDossierItemEntity(id);
        entity.caseId = caseId;
        entity.dossierId = dossierId;
        entity.evidenceId = evidenceId;
        entity.sequenceNo = sequenceNo;
        entity.evidenceSnapshotJson = evidenceSnapshotJson;
        entity.createdAt = now;
        entity.createdBy = actorId;
        return entity;
    }

    public String getEvidenceId() {
        return evidenceId;
    }
}
