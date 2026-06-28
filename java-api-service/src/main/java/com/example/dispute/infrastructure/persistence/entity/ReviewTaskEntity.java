package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ReviewTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "review_task")
public class ReviewTaskEntity extends AbstractEntity {
    @Column(name="case_id",length=64,nullable=false) private String caseId;
    @Column(name="plan_id",length=64,nullable=false) private String planId;
    @Column(name="packet_id",length=64,nullable=false) private String packetId;
    @Enumerated(EnumType.STRING) @Column(name="task_status",length=32,nullable=false) private ReviewTaskStatus taskStatus;
    @Column(name="priority",length=32,nullable=false) private String priority;
    @Column(name="assigned_reviewer_id",length=128) private String assignedReviewerId;
    @Column(name="required_role",length=32,nullable=false) private String requiredRole;
    @Column(name="due_at") private OffsetDateTime dueAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="decision_json",nullable=false,columnDefinition="jsonb") private String decisionJson;
    @Column(name="completed_at") private OffsetDateTime completedAt;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
    @Column(name="created_by",length=128,nullable=false,updatable=false) private String createdBy;
    @Column(name="updated_by",length=128,nullable=false) private String updatedBy;

    protected ReviewTaskEntity() {}
    private ReviewTaskEntity(String id){super(id);}
    public static ReviewTaskEntity pending(
            String id,String caseId,String planId,String packetId,String priority,
            String requiredRole,OffsetDateTime dueAt,String actorId){
        ReviewTaskEntity task=new ReviewTaskEntity(id);task.caseId=caseId;task.planId=planId;
        task.packetId=packetId;task.taskStatus=ReviewTaskStatus.PENDING;task.priority=priority;
        task.requiredRole=requiredRole;task.dueAt=dueAt;task.decisionJson="{}";
        task.createdBy=actorId;task.updatedBy=actorId;return task;
    }
    public void decide(ApprovalDecisionType decision,String reviewerId,String json){
        assignedReviewerId=reviewerId;decisionJson=json;updatedBy=reviewerId;
        taskStatus=switch(decision){
            case APPROVE,MODIFY_AND_APPROVE->ReviewTaskStatus.APPROVED;
            case REJECT->ReviewTaskStatus.REJECTED;
            case REQUEST_MORE_EVIDENCE->ReviewTaskStatus.WAITING_EVIDENCE;
            case ESCALATE_MANUAL->ReviewTaskStatus.ESCALATED;};
        if(taskStatus!=ReviewTaskStatus.WAITING_EVIDENCE)completedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);updatedAt=createdAt;}
    @PreUpdate void preUpdate(){updatedAt=OffsetDateTime.now(ZoneOffset.UTC);}
    public String getCaseId(){return caseId;} public String getPlanId(){return planId;}
    public String getPacketId(){return packetId;} public ReviewTaskStatus getTaskStatus(){return taskStatus;}
    public String getPriority(){return priority;} public String getAssignedReviewerId(){return assignedReviewerId;}
    public String getRequiredRole(){return requiredRole;} public OffsetDateTime getDueAt(){return dueAt;}
    public String getDecisionJson(){return decisionJson;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
