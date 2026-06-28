package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ApprovalDecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "approval_record")
public class ApprovalRecordEntity extends AbstractEntity {
    @Column(name="case_id",length=64,nullable=false) private String caseId;
    @Column(name="review_task_id",length=64,nullable=false) private String reviewTaskId;
    @Column(name="plan_id",length=64,nullable=false) private String planId;
    @Column(name="reviewer_id",length=128,nullable=false) private String reviewerId;
    @Column(name="reviewer_role",length=32,nullable=false) private String reviewerRole;
    @Enumerated(EnumType.STRING) @Column(name="decision_type",length=32,nullable=false) private ApprovalDecisionType decisionType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="original_plan_json",nullable=false,columnDefinition="jsonb") private String originalPlanJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="approved_plan_json",nullable=false,columnDefinition="jsonb") private String approvedPlanJson;
    @Column(name="decision_reason",columnDefinition="text") private String decisionReason;
    @Column(name="approval_hash",length=128,nullable=false,unique=true) private String approvalHash;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="created_by",length=128,nullable=false,updatable=false) private String createdBy;

    protected ApprovalRecordEntity() {}
    private ApprovalRecordEntity(String id){super(id);}
    public static ApprovalRecordEntity record(
            String id,String caseId,String taskId,String planId,String reviewerId,
            String role,ApprovalDecisionType decision,String original,String approved,
            String reason,String hash){
        ApprovalRecordEntity record=new ApprovalRecordEntity(id);record.caseId=caseId;
        record.reviewTaskId=taskId;record.planId=planId;record.reviewerId=reviewerId;
        record.reviewerRole=role;record.decisionType=decision;record.originalPlanJson=original;
        record.approvedPlanJson=approved;record.decisionReason=reason;record.approvalHash=hash;
        record.createdBy=reviewerId;return record;
    }
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);}
    public String getCaseId(){return caseId;} public String getReviewTaskId(){return reviewTaskId;}
    public String getPlanId(){return planId;} public String getReviewerId(){return reviewerId;}
    public ApprovalDecisionType getDecisionType(){return decisionType;}
    public String getOriginalPlanJson(){return originalPlanJson;} public String getApprovedPlanJson(){return approvedPlanJson;}
    public String getDecisionReason(){return decisionReason;}
    public String getApprovalHash(){return approvalHash;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
