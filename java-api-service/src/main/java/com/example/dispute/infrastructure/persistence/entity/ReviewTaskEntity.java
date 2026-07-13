/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射审核任务数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「pending」、「pendingAssigned」、「decide」、「prePersist」、「preUpdate」、「getCaseId」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「ReviewTaskEntity」。
// 类型职责：映射审核任务数据库记录并保存可审计状态；本类型显式提供 「ReviewTaskEntity」、「ReviewTaskEntity」、「pending」、「pendingAssigned」、「decide」、「prePersist」。
// 协作关系：主要由 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.decisionView」、「ReviewApplicationService.view」、「CaseClosureServiceIntegrationTest.seed」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.ReviewTaskEntity()」。
    // 具体功能：「ReviewTaskEntity.ReviewTaskEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ReviewTaskEntity.ReviewTaskEntity()」的上游创建点包括 「ReviewTaskEntity.pending」。
    // 下游影响：「ReviewTaskEntity.ReviewTaskEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewTaskEntity.ReviewTaskEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected ReviewTaskEntity() {}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.ReviewTaskEntity(String)」。
    // 具体功能：「ReviewTaskEntity.ReviewTaskEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ReviewTaskEntity.ReviewTaskEntity(String)」的上游创建点包括 「ReviewTaskEntity.pending」。
    // 下游影响：「ReviewTaskEntity.ReviewTaskEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewTaskEntity.ReviewTaskEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ReviewTaskEntity(String id){super(id);}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.pending(String,String,String,String,String,String,OffsetDateTime,String)」。
    // 具体功能：「ReviewTaskEntity.pending(String,String,String,String,String,String,OffsetDateTime,String)」：更新待处理：先更新内部状态 「caseId」、「planId」、「packetId」、「taskStatus」；处理的关键状态/协议值包括 「{}」，最终返回「ReviewTaskEntity」。
    // 上游调用：「ReviewTaskEntity.pending(String,String,String,String,String,String,OffsetDateTime,String)」的上游调用点包括 「ReviewTaskEntity.pendingAssigned」、「CaseClosureServiceIntegrationTest.seed」、「ToolExecutorServiceIntegrationTest.seed」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「ReviewTaskEntity.pending(String,String,String,String,String,String,OffsetDateTime,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ReviewTaskEntity」交给调用方。
    // 系统意义：「ReviewTaskEntity.pending(String,String,String,String,String,String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ReviewTaskEntity pending(
            String id,String caseId,String planId,String packetId,String priority,
            String requiredRole,OffsetDateTime dueAt,String actorId){
        ReviewTaskEntity task=new ReviewTaskEntity(id);task.caseId=caseId;task.planId=planId;
        task.packetId=packetId;task.taskStatus=ReviewTaskStatus.PENDING;task.priority=priority;
        task.requiredRole=requiredRole;task.dueAt=dueAt;task.decisionJson="{}";
        task.createdBy=actorId;task.updatedBy=actorId;return task;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.pendingAssigned(String,String,String,String,String,String,String,OffsetDateTime,String)」。
    // 具体功能：「ReviewTaskEntity.pendingAssigned(String,String,String,String,String,String,String,OffsetDateTime,String)」：更新待处理Assigned：先更新内部状态 「assignedReviewerId」；实际协作者为 「pending」，最终返回「ReviewTaskEntity」。
    // 上游调用：「ReviewTaskEntity.pendingAssigned(String,String,String,String,String,String,String,OffsetDateTime,String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」。
    // 下游影响：「ReviewTaskEntity.pendingAssigned(String,String,String,String,String,String,String,OffsetDateTime,String)」向下依次触达 「pending」；计算结果以「ReviewTaskEntity」交给调用方。
    // 系统意义：「ReviewTaskEntity.pendingAssigned(String,String,String,String,String,String,String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ReviewTaskEntity pendingAssigned(
            String id,String caseId,String planId,String packetId,String priority,
            String requiredRole,String assignedReviewerId,OffsetDateTime dueAt,String actorId){
        ReviewTaskEntity task=pending(
                id,caseId,planId,packetId,priority,requiredRole,dueAt,actorId);
        task.assignedReviewerId=assignedReviewerId;return task;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.decide(ApprovalDecisionType,String,String)」。
    // 具体功能：「ReviewTaskEntity.decide(ApprovalDecisionType,String,String)」：作出决定审核任务：先更新内部状态 「assignedReviewerId」、「decisionJson」、「updatedBy」、「taskStatus」，最终返回「void」。
    // 上游调用：「ReviewTaskEntity.decide(ApprovalDecisionType,String,String)」由使用「ReviewTaskEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewTaskEntity.decide(ApprovalDecisionType,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewTaskEntity.decide(ApprovalDecisionType,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void decide(ApprovalDecisionType decision,String reviewerId,String json){
        assignedReviewerId=reviewerId;decisionJson=json;updatedBy=reviewerId;
        taskStatus=switch(decision){
            case APPROVE,MODIFY_AND_APPROVE->ReviewTaskStatus.APPROVED;
            case REJECT->ReviewTaskStatus.REJECTED;
            case REQUEST_MORE_EVIDENCE->ReviewTaskStatus.WAITING_EVIDENCE;
            case ESCALATE_MANUAL->ReviewTaskStatus.ESCALATED;};
        if(taskStatus!=ReviewTaskStatus.WAITING_EVIDENCE)completedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.prePersist()」。
    // 具体功能：「ReviewTaskEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「ReviewTaskEntity.prePersist()」由使用「ReviewTaskEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewTaskEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewTaskEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);updatedAt=createdAt;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.preUpdate()」。
    // 具体功能：「ReviewTaskEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「ReviewTaskEntity.preUpdate()」由使用「ReviewTaskEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewTaskEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewTaskEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate void preUpdate(){updatedAt=OffsetDateTime.now(ZoneOffset.UTC);}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getCaseId()」。
    // 具体功能：「ReviewTaskEntity.getCaseId()」：读取「ReviewTaskEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getCaseId()」的上游调用点包括 「ReviewApplicationService.decisionView」、「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId(){return caseId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getPlanId()」。
    // 具体功能：「ReviewTaskEntity.getPlanId()」：读取「ReviewTaskEntity」中的「planId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getPlanId()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getPlanId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getPlanId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPlanId(){return planId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getPacketId()」。
    // 具体功能：「ReviewTaskEntity.getPacketId()」：读取「ReviewTaskEntity」中的「packetId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getPacketId()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getPacketId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getPacketId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPacketId(){return packetId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getTaskStatus()」。
    // 具体功能：「ReviewTaskEntity.getTaskStatus()」：读取「ReviewTaskEntity」中的「taskStatus」状态，向 JPA、应用服务或序列化层返回「ReviewTaskStatus」。
    // 上游调用：「ReviewTaskEntity.getTaskStatus()」的上游调用点包括 「ReviewApplicationService.decisionView」、「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getTaskStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ReviewTaskStatus」交给调用方。
    // 系统意义：「ReviewTaskEntity.getTaskStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public ReviewTaskStatus getTaskStatus(){return taskStatus;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getPriority()」。
    // 具体功能：「ReviewTaskEntity.getPriority()」：读取「ReviewTaskEntity」中的「priority」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getPriority()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getPriority()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getPriority()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPriority(){return priority;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getAssignedReviewerId()」。
    // 具体功能：「ReviewTaskEntity.getAssignedReviewerId()」：读取「ReviewTaskEntity」中的「assignedReviewerId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getAssignedReviewerId()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getAssignedReviewerId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getAssignedReviewerId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAssignedReviewerId(){return assignedReviewerId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getRequiredRole()」。
    // 具体功能：「ReviewTaskEntity.getRequiredRole()」：读取「ReviewTaskEntity」中的「requiredRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getRequiredRole()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getRequiredRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getRequiredRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRequiredRole(){return requiredRole;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getDueAt()」。
    // 具体功能：「ReviewTaskEntity.getDueAt()」：读取「ReviewTaskEntity」中的「dueAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ReviewTaskEntity.getDueAt()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getDueAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ReviewTaskEntity.getDueAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getDueAt(){return dueAt;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getDecisionJson()」。
    // 具体功能：「ReviewTaskEntity.getDecisionJson()」：读取「ReviewTaskEntity」中的「decisionJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewTaskEntity.getDecisionJson()」由使用「ReviewTaskEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewTaskEntity.getDecisionJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewTaskEntity.getDecisionJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDecisionJson(){return decisionJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewTaskEntity.getCreatedAt()」。
    // 具体功能：「ReviewTaskEntity.getCreatedAt()」：读取「ReviewTaskEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ReviewTaskEntity.getCreatedAt()」的上游调用点包括 「ReviewApplicationService.view」。
    // 下游影响：「ReviewTaskEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ReviewTaskEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt(){return createdAt;}
}
