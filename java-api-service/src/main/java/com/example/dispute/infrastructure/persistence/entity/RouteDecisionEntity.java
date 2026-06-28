package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "route_decision")
public class RouteDecisionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_type", length = 64, nullable = false)
    private RouteType routeType;

    @Column(name = "reason_code", length = 128, nullable = false)
    private String reasonCode;

    @Column(name = "reason_detail", nullable = false, columnDefinition = "text")
    private String reasonDetail;

    @Column(name = "requires_additional_evidence", nullable = false)
    private boolean requiresAdditionalEvidence;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @Column(name = "policy_rule_id", length = 64)
    private String policyRuleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected RouteDecisionEntity() {}

    private RouteDecisionEntity(
            String id,
            String caseId,
            String idempotencyKey,
            RouteType routeType,
            String reasonCode,
            String reasonDetail,
            boolean requiresAdditionalEvidence,
            int dossierVersion,
            String policyRuleId,
            String inputSnapshotJson,
            String actorId) {
        super(id);
        this.caseId = required(caseId, "caseId");
        this.idempotencyKey = required(idempotencyKey, "idempotencyKey");
        this.routeType = Objects.requireNonNull(routeType, "routeType must not be null");
        this.reasonCode = required(reasonCode, "reasonCode");
        this.reasonDetail = required(reasonDetail, "reasonDetail");
        this.requiresAdditionalEvidence = requiresAdditionalEvidence;
        this.dossierVersion = dossierVersion;
        this.policyRuleId = policyRuleId;
        this.inputSnapshotJson = required(inputSnapshotJson, "inputSnapshotJson");
        this.createdBy = required(actorId, "actorId");
    }

    public static RouteDecisionEntity record(
            String id,
            String caseId,
            String idempotencyKey,
            RouteType routeType,
            String reasonCode,
            String reasonDetail,
            boolean requiresAdditionalEvidence,
            int dossierVersion,
            String policyRuleId,
            String inputSnapshotJson,
            String actorId) {
        return new RouteDecisionEntity(
                id,
                caseId,
                idempotencyKey,
                routeType,
                reasonCode,
                reasonDetail,
                requiresAdditionalEvidence,
                dossierVersion,
                policyRuleId,
                inputSnapshotJson,
                actorId);
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public RouteType getRouteType() {
        return routeType;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public boolean isRequiresAdditionalEvidence() {
        return requiresAdditionalEvidence;
    }

    public int getDossierVersion() {
        return dossierVersion;
    }

    public String getPolicyRuleId() {
        return policyRuleId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
