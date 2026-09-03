/*
 * 所属模块：共享小法庭。
 * 文件职责：表达和解版本Conflict失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import java.util.Map;

// 所属模块：【共享小法庭 / 应用编排层】类型「SettlementVersionConflictException」。
// 类型职责：表达和解版本Conflict失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「SettlementVersionConflictException」。
// 协作关系：主要由 「SettlementService.confirm」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class SettlementVersionConflictException extends BusinessException {

    // 所属模块：【共享小法庭 / 应用编排层】「SettlementVersionConflictException.SettlementVersionConflictException(String,int,int)」。
    // 具体功能：「SettlementVersionConflictException.SettlementVersionConflictException(String,int,int)」：把 「caseId」(String)、「requestedVersion」(int)、「currentVersion」(int) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「SettlementVersionConflictException.SettlementVersionConflictException(String,int,int)」的上游创建点包括 「SettlementService.confirm」。
    // 下游影响：「SettlementVersionConflictException.SettlementVersionConflictException(String,int,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementVersionConflictException.SettlementVersionConflictException(String,int,int)」负责主链路中的“和解版本Conflict异常”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public SettlementVersionConflictException(
            String caseId, int requestedVersion, int currentVersion) {
        super(
                ErrorCode.CASE_STATUS_INVALID,
                "settlement version is no longer current",
                Map.of(
                        "case_id", caseId,
                        "requested_version", requestedVersion,
                        "current_version", currentVersion));
    }
}
