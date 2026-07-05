package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_intake_dossier")
public class CaseIntakeDossierEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dossier_json", nullable = false, columnDefinition = "jsonb")
    private String dossierJson;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Column(name = "ready_for_next_step", nullable = false)
    private boolean readyForNextStep;

    @Column(name = "admission_recommendation", length = 32, nullable = false)
    private String admissionRecommendation;

    @Column(name = "source_turn_no", nullable = false)
    private int sourceTurnNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected CaseIntakeDossierEntity() {}

    private CaseIntakeDossierEntity(String id) {
        super(required(id, "id"));
    }

    public static CaseIntakeDossierEntity create(
            String id,
            String caseId,
            RoomType roomType,
            String dossierJson,
            int qualityScore,
            boolean readyForNextStep,
            String admissionRecommendation,
            int sourceTurnNo,
            String actorId) {
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("case intake dossier only supports INTAKE room");
        }
        CaseIntakeDossierEntity entity = new CaseIntakeDossierEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.dossierVersion = 1;
        entity.dossierJson = required(dossierJson, "dossierJson");
        entity.qualityScore = clampQuality(qualityScore);
        entity.readyForNextStep = readyForNextStep;
        entity.admissionRecommendation = required(admissionRecommendation, "admissionRecommendation");
        entity.sourceTurnNo = positive(sourceTurnNo, "sourceTurnNo");
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = entity.createdBy;
        return entity;
    }

    public void replaceWith(
            String dossierJson,
            int qualityScore,
            boolean readyForNextStep,
            String admissionRecommendation,
            int sourceTurnNo,
            String actorId) {
        dossierVersion += 1;
        this.dossierJson = required(dossierJson, "dossierJson");
        this.qualityScore = clampQuality(qualityScore);
        this.readyForNextStep = readyForNextStep;
        this.admissionRecommendation = required(admissionRecommendation, "admissionRecommendation");
        this.sourceTurnNo = positive(sourceTurnNo, "sourceTurnNo");
        this.updatedBy = required(actorId, "actorId");
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() {
        return caseId;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public int getDossierVersion() {
        return dossierVersion;
    }

    public String getDossierJson() {
        return dossierJson;
    }

    public int getQualityScore() {
        return qualityScore;
    }

    public boolean isReadyForNextStep() {
        return readyForNextStep;
    }

    public String getAdmissionRecommendation() {
        return admissionRecommendation;
    }

    public int getSourceTurnNo() {
        return sourceTurnNo;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int clampQuality(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

