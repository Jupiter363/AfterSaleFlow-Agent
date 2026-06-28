package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "policy_rule")
public class PolicyRuleEntity extends AbstractEntity {

    @Column(name = "rule_code", length = 128, nullable = false)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "rule_name", length = 256, nullable = false)
    private String ruleName;

    @Column(name = "rule_scope", length = 64, nullable = false)
    private String ruleScope;

    @Column(name = "rule_status", length = 32, nullable = false)
    private String ruleStatus;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "priority", nullable = false)
    private int priority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json", nullable = false, columnDefinition = "jsonb")
    private String conditionJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome_json", nullable = false, columnDefinition = "jsonb")
    private String outcomeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_document_json", nullable = false, columnDefinition = "jsonb")
    private String sourceDocumentJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected PolicyRuleEntity() {}

    private PolicyRuleEntity(
            String id,
            String ruleCode,
            int ruleVersion,
            String ruleName,
            String ruleScope,
            OffsetDateTime effectiveFrom,
            int priority,
            String conditionJson,
            String outcomeJson,
            String sourceDocumentJson,
            String actorId) {
        super(id);
        this.ruleCode = ruleCode;
        this.ruleVersion = ruleVersion;
        this.ruleName = ruleName;
        this.ruleScope = ruleScope;
        this.ruleStatus = "ACTIVE";
        this.effectiveFrom = effectiveFrom;
        this.priority = priority;
        this.conditionJson = conditionJson;
        this.outcomeJson = outcomeJson;
        this.sourceDocumentJson = sourceDocumentJson;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public static PolicyRuleEntity active(
            String id,
            String ruleCode,
            int ruleVersion,
            String ruleName,
            String ruleScope,
            OffsetDateTime effectiveFrom,
            int priority,
            String conditionJson,
            String outcomeJson,
            String sourceDocumentJson,
            String actorId) {
        return new PolicyRuleEntity(
                id,
                ruleCode,
                ruleVersion,
                ruleName,
                ruleScope,
                effectiveFrom,
                priority,
                conditionJson,
                outcomeJson,
                sourceDocumentJson,
                actorId);
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

    public String getRuleCode() {
        return ruleCode;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getRuleScope() {
        return ruleScope;
    }

    public String getRuleStatus() {
        return ruleStatus;
    }

    public OffsetDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public OffsetDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public int getPriority() {
        return priority;
    }

    public String getConditionJson() {
        return conditionJson;
    }

    public String getOutcomeJson() {
        return outcomeJson;
    }

    public String getSourceDocumentJson() {
        return sourceDocumentJson;
    }
}
