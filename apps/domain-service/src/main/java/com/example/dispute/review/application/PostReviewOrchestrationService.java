/*
 * 所属模块：平台人工终审。
 * 文件职责：编排人工批准后的执行和结案交接规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「orchestrate」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.room.application.CaseEventService;
import java.util.Map;
import org.springframework.stereotype.Service;

// 所属模块：【平台人工终审 / 应用编排层】类型「PostReviewOrchestrationService」。
// 类型职责：编排人工批准后的执行和结案交接规则、权限校验与事实读写；本类型显式提供 「PostReviewOrchestrationService」、「orchestrate」、「audit」、「isExecutable」。
// 协作关系：主要由 「ReviewApplicationService.decide」、「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」、「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions」、「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class PostReviewOrchestrationService {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("post-review-orchestrator", ActorRole.SYSTEM);
    private static final String EXECUTION_ASSISTANT_HANDOFF =
            "EXECUTION_ASSISTANT_HANDOFF";

    private final ApprovalRecordRepository approvalRepository;
    private final AuditRecorder auditRecorder;
    private final CaseEventService caseEventService;

    // 所属模块：【平台人工终审 / 应用编排层】「PostReviewOrchestrationService.PostReviewOrchestrationService(ApprovalRecordRepository,AuditRecorder,CaseEventService)」。
    // 具体功能：「PostReviewOrchestrationService.PostReviewOrchestrationService(ApprovalRecordRepository,AuditRecorder,CaseEventService)」：通过构造器接收 「approvalRepository」(ApprovalRecordRepository)、「auditRecorder」(AuditRecorder)、「caseEventService」(CaseEventService) 并保存为「PostReviewOrchestrationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「PostReviewOrchestrationService.PostReviewOrchestrationService(ApprovalRecordRepository,AuditRecorder,CaseEventService)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「PostReviewOrchestrationService.PostReviewOrchestrationService(ApprovalRecordRepository,AuditRecorder,CaseEventService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PostReviewOrchestrationService.PostReviewOrchestrationService(ApprovalRecordRepository,AuditRecorder,CaseEventService)」负责主链路中的“事务后审核Orchestration服务”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public PostReviewOrchestrationService(
            ApprovalRecordRepository approvalRepository,
            AuditRecorder auditRecorder,
            CaseEventService caseEventService) {
        this.approvalRepository = approvalRepository;
        this.auditRecorder = auditRecorder;
        this.caseEventService = caseEventService;
    }

    // 所属模块：【平台人工终审 / 应用编排层】「PostReviewOrchestrationService.orchestrate(String,AuthenticatedActor,String)」。
    // 具体功能：「PostReviewOrchestrationService.orchestrate(String,AuthenticatedActor,String)」：编排事务后审核Orchestration：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「approvalRepository.findById」、「caseEventService.recordLifecycleEvent」、「approval.getDecisionType」、「approval.getCaseId」；处理的关键状态/协议值包括 「approval_record_id」、「WAITING_EVIDENCE」、「MANUAL_HANDOFF」、「POST_REVIEW_NO_EXECUTION」，最终返回「PostReviewOrchestrationResult」。
    // 上游调用：「PostReviewOrchestrationService.orchestrate(String,AuthenticatedActor,String)」的上游调用点包括 「ReviewApplicationService.decide」、「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」、「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions」、「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions」。
    // 下游影响：「PostReviewOrchestrationService.orchestrate(String,AuthenticatedActor,String)」向下依次触达 「approvalRepository.findById」、「caseEventService.recordLifecycleEvent」、「approval.getDecisionType」、「approval.getCaseId」；计算结果以「PostReviewOrchestrationResult」交给调用方。
    // 系统意义：「PostReviewOrchestrationService.orchestrate(String,AuthenticatedActor,String)」负责主链路中的“事务后审核Orchestration”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    public PostReviewOrchestrationResult orchestrate(
            String approvalRecordId,
            AuthenticatedActor reviewer,
            String idempotencyKey) {
        ApprovalRecordEntity approval =
                approvalRepository
                        .findById(approvalRecordId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "approval record not found",
                                                Map.of("approval_record_id", approvalRecordId)));
        ApprovalDecisionType decision = approval.getDecisionType();
        if (!isExecutable(decision)) {
            String status =
                    decision == ApprovalDecisionType.REQUEST_MORE_EVIDENCE
                            ? "WAITING_EVIDENCE"
                            : "MANUAL_HANDOFF";
            audit("POST_REVIEW_NO_EXECUTION", approval, reviewer, status, null);
            return new PostReviewOrchestrationResult(
                    approvalRecordId,
                    approval.getCaseId(),
                    status,
                    false,
                    false,
                    "review decision does not allow execution");
        }

        audit(
                "POST_REVIEW_EXECUTION_ASSISTANT_HANDOFF",
                approval,
                reviewer,
                EXECUTION_ASSISTANT_HANDOFF,
                null);
        caseEventService.recordLifecycleEvent(
                approval.getCaseId(),
                null,
                EXECUTION_ASSISTANT_HANDOFF,
                Map.of(
                        "approval_record_id",
                        approvalRecordId,
                        "decision",
                        decision.name(),
                        "status",
                        EXECUTION_ASSISTANT_HANDOFF,
                        "reviewer_id",
                        reviewer.actorId(),
                        "message",
                        "final decision confirmed and handed off to execution assistant"),
                "post-review-execution-assistant:" + approvalRecordId,
                reviewer.actorId());
        return new PostReviewOrchestrationResult(
                approvalRecordId,
                approval.getCaseId(),
                EXECUTION_ASSISTANT_HANDOFF,
                false,
                false,
                "final decision confirmed and handed off to execution assistant");
    }

    // 所属模块：【平台人工终审 / 应用编排层】「PostReviewOrchestrationService.audit(String,ApprovalRecordEntity,AuthenticatedActor,String,String)」。
    // 具体功能：「PostReviewOrchestrationService.audit(String,ApprovalRecordEntity,AuthenticatedActor,String,String)」：执行审计；实际协作者为 「auditRecorder.record」、「approval.getId」、「approval.getCaseId」、「approval.getDecisionType」；处理的关键状态/协议值包括 「APPROVAL_RECORD」、「decision」、「status」、「reviewer_id」，最终返回「void」。
    // 上游调用：「PostReviewOrchestrationService.audit(String,ApprovalRecordEntity,AuthenticatedActor,String,String)」的上游调用点包括 「PostReviewOrchestrationService.orchestrate」。
    // 下游影响：「PostReviewOrchestrationService.audit(String,ApprovalRecordEntity,AuthenticatedActor,String,String)」向下依次触达 「auditRecorder.record」、「approval.getId」、「approval.getCaseId」、「approval.getDecisionType」。
    // 系统意义：「PostReviewOrchestrationService.audit(String,ApprovalRecordEntity,AuthenticatedActor,String,String)」负责主链路中的“审计”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private void audit(
            String action,
            ApprovalRecordEntity approval,
            AuthenticatedActor reviewer,
            String status,
            String errorType) {
        auditRecorder.record(
                SYSTEM,
                action,
                "APPROVAL_RECORD",
                approval.getId(),
                approval.getCaseId(),
                Map.of("decision", approval.getDecisionType().name()),
                errorType == null
                        ? Map.of(
                                "status",
                                status,
                                "reviewer_id",
                                reviewer.actorId())
                        : Map.of(
                                "status",
                                status,
                                "reviewer_id",
                                reviewer.actorId(),
                                "error_type",
                                errorType));
    }

    // 所属模块：【平台人工终审 / 应用编排层】「PostReviewOrchestrationService.isExecutable(ApprovalDecisionType)」。
    // 具体功能：「PostReviewOrchestrationService.isExecutable(ApprovalDecisionType)」：判断是否Executable，最终返回「boolean」。
    // 上游调用：「PostReviewOrchestrationService.isExecutable(ApprovalDecisionType)」的上游调用点包括 「PostReviewOrchestrationService.orchestrate」。
    // 下游影响：「PostReviewOrchestrationService.isExecutable(ApprovalDecisionType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「PostReviewOrchestrationService.isExecutable(ApprovalDecisionType)」负责主链路中的“Executable”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static boolean isExecutable(ApprovalDecisionType decision) {
        return decision == ApprovalDecisionType.APPROVE
                || decision == ApprovalDecisionType.MODIFY_AND_APPROVE;
    }
}
