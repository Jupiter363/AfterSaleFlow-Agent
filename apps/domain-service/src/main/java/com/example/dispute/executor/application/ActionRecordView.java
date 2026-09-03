/*
 * 所属模块：确定性工具执行。
 * 文件职责：定义动作记录跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.application;

import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

// 所属模块：【确定性工具执行 / 应用编排层】类型「ActionRecordView」。
// 类型职责：定义动作记录跨层传递时使用的不可变数据契约；本类型显式提供 「ActionRecordView」。
// 协作关系：主要由 「ToolExecutorService.view」、「ExecutionControllerTest.action」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ActionRecordView(
        String actionRecordId,
        String caseId,
        String planId,
        String approvalRecordId,
        String actionType,
        RiskLevel riskLevel,
        String idempotencyKey,
        String approvedBy,
        String executedBy,
        ExecutionStatus executionStatus,
        int attemptCount,
        JsonNode request,
        JsonNode result,
        String errorCode,
        String errorMessage,
        String reviewPacketId,
        String actionSnapshotHash,
        JsonNode evidenceRefs,
        JsonNode ruleRefs,
        JsonNode agentRunRefs,
        String externalResultRef,
        OffsetDateTime executionTime,
        OffsetDateTime createdAt) {

    // 所属模块：【确定性工具执行 / 应用编排层】「ActionRecordView.ActionRecordView(String,String,String,String,String,RiskLevel,String,String,String,ExecutionStatus,int,JsonNode,JsonNode,String,String,OffsetDateTime,OffsetDateTime)」。
    // 具体功能：「ActionRecordView.ActionRecordView(String,String,String,String,String,RiskLevel,String,String,String,ExecutionStatus,int,JsonNode,JsonNode,String,String,OffsetDateTime,OffsetDateTime)」：使用 「actionRecordId」(String)、「caseId」(String)、「planId」(String)、「approvalRecordId」(String)、「actionType」(String)、「riskLevel」(RiskLevel)、「idempotencyKey」(String)、「approvedBy」(String)、「executedBy」(String)、「executionStatus」(ExecutionStatus)、「attemptCount」(int)、「request」(JsonNode)、「result」(JsonNode)、「errorCode」(String)、「errorMessage」(String)、「executionTime」(OffsetDateTime)、「createdAt」(OffsetDateTime) 初始化「ActionRecordView」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「ActionRecordView.ActionRecordView(String,String,String,String,String,RiskLevel,String,String,String,ExecutionStatus,int,JsonNode,JsonNode,String,String,OffsetDateTime,OffsetDateTime)」的上游创建点包括 「ToolExecutorService.view」、「ExecutionControllerTest.action」。
    // 下游影响：「ActionRecordView.ActionRecordView(String,String,String,String,String,RiskLevel,String,String,String,ExecutionStatus,int,JsonNode,JsonNode,String,String,OffsetDateTime,OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordView.ActionRecordView(String,String,String,String,String,RiskLevel,String,String,String,ExecutionStatus,int,JsonNode,JsonNode,String,String,OffsetDateTime,OffsetDateTime)」负责主链路中的“动作记录视图”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ActionRecordView(
            String actionRecordId,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            ExecutionStatus executionStatus,
            int attemptCount,
            JsonNode request,
            JsonNode result,
            String errorCode,
            String errorMessage,
            OffsetDateTime executionTime,
            OffsetDateTime createdAt) {
        this(
                actionRecordId,
                caseId,
                planId,
                approvalRecordId,
                actionType,
                riskLevel,
                idempotencyKey,
                approvedBy,
                executedBy,
                executionStatus,
                attemptCount,
                request,
                result,
                errorCode,
                errorMessage,
                null,
                null,
                null,
                null,
                null,
                null,
                executionTime,
                createdAt);
    }
}
