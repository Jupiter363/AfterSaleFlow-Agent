/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义模拟外部争议跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.RiskLevel;

// 所属模块：【案件核心与导入 / 应用编排层】类型「SimulatedExternalDispute」。
// 类型职责：定义模拟外部争议跨层传递时使用的不可变数据契约；本类型显式提供 「SimulatedExternalDispute」、「requireText」、「optionalText」。
// 协作关系：主要由 「AgentSimulatedExternalDispute.toDomain」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SimulatedExternalDispute(
        String sourceSystem,
        String externalCaseReference,
        String orderReference,
        String afterSalesReference,
        String logisticsReference,
        String userId,
        String merchantId,
        String initiatorRole,
        String disputeType,
        String title,
        String description,
        RiskLevel riskLevel) {

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDispute.SimulatedExternalDispute(String,String,String,String,String,String,String,String,String,String,String,RiskLevel)」。
    // 具体功能：「SimulatedExternalDispute.SimulatedExternalDispute(String,String,String,String,String,String,String,String,String,String,String,RiskLevel)」：在不可变「SimulatedExternalDispute」写入组件前校验 「sourceSystem」(String)、「externalCaseReference」(String)、「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「userId」(String)、「merchantId」(String)、「initiatorRole」(String)、「disputeType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)，非法输入会抛出 「IllegalArgumentException」；并通过 「DemoImportActors.requireImportedParties」、「requireText」、「optionalText」 做标准化或防御性复制。
    // 上游调用：「SimulatedExternalDispute.SimulatedExternalDispute(String,String,String,String,String,String,String,String,String,String,String,RiskLevel)」的上游创建点包括 「AgentSimulatedExternalDispute.toDomain」。
    // 下游影响：「SimulatedExternalDispute.SimulatedExternalDispute(String,String,String,String,String,String,String,String,String,String,String,RiskLevel)」向下依次触达 「DemoImportActors.requireImportedParties」、「requireText」、「optionalText」。
    // 系统意义：「SimulatedExternalDispute.SimulatedExternalDispute(String,String,String,String,String,String,String,String,String,String,String,RiskLevel)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public SimulatedExternalDispute {
        requireText(sourceSystem, 64, "sourceSystem");
        requireText(externalCaseReference, 128, "externalCaseReference");
        requireText(orderReference, 64, "orderReference");
        optionalText(afterSalesReference, 64, "afterSalesReference");
        optionalText(logisticsReference, 64, "logisticsReference");
        requireText(userId, 128, "userId");
        requireText(merchantId, 128, "merchantId");
        DemoImportActors.requireImportedParties(userId, merchantId);
        requireText(initiatorRole, 32, "initiatorRole");
        if (!"USER".equals(initiatorRole) && !"MERCHANT".equals(initiatorRole)) {
            throw new IllegalArgumentException(
                    "initiatorRole must be USER or MERCHANT");
        }
        requireText(disputeType, 64, "disputeType");
        requireText(title, 256, "title");
        requireText(description, 2000, "description");
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDispute.requireText(String,int,String)」。
    // 具体功能：「SimulatedExternalDispute.requireText(String,int,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「SimulatedExternalDispute.requireText(String,int,String)」的上游调用点包括 「SimulatedExternalDispute.SimulatedExternalDispute」。
    // 下游影响：「SimulatedExternalDispute.requireText(String,int,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedExternalDispute.requireText(String,int,String)」在“文本”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(
            String value,
            int maxLength,
            String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDispute.optionalText(String,int,String)」。
    // 具体功能：「SimulatedExternalDispute.optionalText(String,int,String)」：执行可选文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「SimulatedExternalDispute.optionalText(String,int,String)」的上游调用点包括 「SimulatedExternalDispute.SimulatedExternalDispute」。
    // 下游影响：「SimulatedExternalDispute.optionalText(String,int,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedExternalDispute.optionalText(String,int,String)」负责主链路中的“可选文本”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void optionalText(
            String value,
            int maxLength,
            String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
    }
}
