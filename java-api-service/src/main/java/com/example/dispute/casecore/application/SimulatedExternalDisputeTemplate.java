/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义模拟外部争议模板跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「forInitiator」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;

// 所属模块：【案件核心与导入 / 应用编排层】类型「SimulatedExternalDisputeTemplate」。
// 类型职责：定义模拟外部争议模板跨层传递时使用的不可变数据契约；本类型显式提供 「SimulatedExternalDisputeTemplate」、「requireText」、「forInitiator」、「merchantRequestedResolution」、「merchantRequestedAmount」、「respondentAttitudeForMerchantInitiator」。
// 协作关系：主要由 「ExternalCaseImportTransactionService.simulatedCommand」、「SimulatedExternalDisputeTemplateCatalog.template」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SimulatedExternalDisputeTemplate(
        int templateNo,
        String title,
        String description,
        String originalStatement,
        String disputeType,
        RiskLevel riskLevel,
        String requestedResolution,
        BigDecimal requestedAmount,
        String requestedItems,
        String requestReason,
        String respondentAttitude,
        String respondentPosition) {

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate(int,String,String,String,String,RiskLevel,String,BigDecimal,String,String,String,String)」。
    // 具体功能：「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate(int,String,String,String,String,RiskLevel,String,BigDecimal,String,String,String,String)」：在不可变「SimulatedExternalDisputeTemplate」写入组件前校验 「templateNo」(int)、「title」(String)、「description」(String)、「originalStatement」(String)、「disputeType」(String)、「riskLevel」(RiskLevel)、「requestedResolution」(String)、「requestedAmount」(BigDecimal)、「requestedItems」(String)、「requestReason」(String)、「respondentAttitude」(String)、「respondentPosition」(String)，非法输入会抛出 「IllegalArgumentException」；并通过 「requireText」 做标准化或防御性复制。
    // 上游调用：「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate(int,String,String,String,String,RiskLevel,String,BigDecimal,String,String,String,String)」的上游创建点包括 「SimulatedExternalDisputeTemplateCatalog.template」。
    // 下游影响：「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate(int,String,String,String,String,RiskLevel,String,BigDecimal,String,String,String,String)」向下依次触达 「requireText」。
    // 系统意义：「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate(int,String,String,String,String,RiskLevel,String,BigDecimal,String,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public SimulatedExternalDisputeTemplate {
        if (templateNo < 1 || templateNo > 20) {
            throw new IllegalArgumentException("templateNo must be between 1 and 20");
        }
        requireText(title, "title");
        requireText(description, "description");
        requireText(originalStatement, "originalStatement");
        requireText(disputeType, "disputeType");
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
        requireText(requestedResolution, "requestedResolution");
        requireText(requestedItems, "requestedItems");
        requireText(requestReason, "requestReason");
        requireText(respondentAttitude, "respondentAttitude");
        requireText(respondentPosition, "respondentPosition");
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.requireText(String,String)」。
    // 具体功能：「SimulatedExternalDisputeTemplate.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「SimulatedExternalDisputeTemplate.requireText(String,String)」的上游调用点包括 「SimulatedExternalDisputeTemplate.SimulatedExternalDisputeTemplate」。
    // 下游影响：「SimulatedExternalDisputeTemplate.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedExternalDisputeTemplate.requireText(String,String)」在“文本”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.forInitiator(ActorRole)」。
    // 具体功能：「SimulatedExternalDisputeTemplate.forInitiator(ActorRole)」：构建面向发起方；实际协作者为 「asMerchantFirstPerson」、「merchantRequestedResolution」、「merchantRequestedAmount」、「respondentAttitudeForMerchantInitiator」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「希望平台核验双方陈述和相关材料后确认处理方向。」、「用户的诉求是：」、「对方主张：」，最终返回「InitiatorPerspective」。
    // 上游调用：「SimulatedExternalDisputeTemplate.forInitiator(ActorRole)」的上游调用点包括 「ExternalCaseImportTransactionService.simulatedCommand」。
    // 下游影响：「SimulatedExternalDisputeTemplate.forInitiator(ActorRole)」向下依次触达 「asMerchantFirstPerson」、「merchantRequestedResolution」、「merchantRequestedAmount」、「respondentAttitudeForMerchantInitiator」；计算结果以「InitiatorPerspective」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplate.forInitiator(ActorRole)」负责主链路中的“面向发起方”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public InitiatorPerspective forInitiator(ActorRole initiatorRole) {
        if (initiatorRole == null) {
            throw new IllegalArgumentException("initiatorRole must not be null");
        }
        if (initiatorRole == ActorRole.USER) {
            return new InitiatorPerspective(
                    requestedResolution,
                    requestedAmount,
                    requestedItems,
                    requestReason,
                    originalStatement,
                    respondentAttitude,
                    respondentPosition);
        }
        if (initiatorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException(
                    "simulated dispute initiator must be USER or MERCHANT");
        }

        String merchantPosition = asMerchantFirstPerson(respondentPosition);
        return new InitiatorPerspective(
                merchantRequestedResolution(),
                merchantRequestedAmount(),
                requestedItems,
                merchantPosition + "希望平台核验双方陈述和相关材料后确认处理方向。",
                merchantPosition + "用户的诉求是：" + requestReason,
                respondentAttitudeForMerchantInitiator(),
                "对方主张：" + requestReason);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.merchantRequestedResolution()」。
    // 具体功能：「SimulatedExternalDisputeTemplate.merchantRequestedResolution()」：构建商家请求Resolution；处理的关键状态/协议值包括 「AGREE」、「VERIFY_OR_EXPLAIN_ONLY」，最终返回「String」。
    // 上游调用：「SimulatedExternalDisputeTemplate.merchantRequestedResolution()」的上游调用点包括 「SimulatedExternalDisputeTemplate.forInitiator」。
    // 下游影响：「SimulatedExternalDisputeTemplate.merchantRequestedResolution()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplate.merchantRequestedResolution()」负责主链路中的“商家请求Resolution”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private String merchantRequestedResolution() {
        return "AGREE".equals(respondentAttitude)
                ? requestedResolution
                : "VERIFY_OR_EXPLAIN_ONLY";
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.merchantRequestedAmount()」。
    // 具体功能：「SimulatedExternalDisputeTemplate.merchantRequestedAmount()」：构建商家请求Amount；处理的关键状态/协议值包括 「AGREE」，最终返回「BigDecimal」。
    // 上游调用：「SimulatedExternalDisputeTemplate.merchantRequestedAmount()」的上游调用点包括 「SimulatedExternalDisputeTemplate.forInitiator」。
    // 下游影响：「SimulatedExternalDisputeTemplate.merchantRequestedAmount()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「BigDecimal」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplate.merchantRequestedAmount()」负责主链路中的“商家请求Amount”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private BigDecimal merchantRequestedAmount() {
        return "AGREE".equals(respondentAttitude) ? requestedAmount : null;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.respondentAttitudeForMerchantInitiator()」。
    // 具体功能：「SimulatedExternalDisputeTemplate.respondentAttitudeForMerchantInitiator()」：构建被申请方态度面向商家发起方；处理的关键状态/协议值包括 「NEED_MORE_INFO」、「ALTERNATIVE_PROPOSED」，最终返回「String」。
    // 上游调用：「SimulatedExternalDisputeTemplate.respondentAttitudeForMerchantInitiator()」的上游调用点包括 「SimulatedExternalDisputeTemplate.forInitiator」。
    // 下游影响：「SimulatedExternalDisputeTemplate.respondentAttitudeForMerchantInitiator()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplate.respondentAttitudeForMerchantInitiator()」负责主链路中的“被申请方态度面向商家发起方”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private String respondentAttitudeForMerchantInitiator() {
        return switch (respondentAttitude) {
            case "NEED_MORE_INFO" -> "ALTERNATIVE_PROPOSED";
            default -> respondentAttitude;
        };
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.asMerchantFirstPerson(String)」。
    // 具体功能：「SimulatedExternalDisputeTemplate.asMerchantFirstPerson(String)」：构建as商家首版Person；处理的关键状态/协议值包括 「对方称」、「我们说明」、「对方」、「我们」，最终返回「String」。
    // 上游调用：「SimulatedExternalDisputeTemplate.asMerchantFirstPerson(String)」的上游调用点包括 「SimulatedExternalDisputeTemplate.forInitiator」。
    // 下游影响：「SimulatedExternalDisputeTemplate.asMerchantFirstPerson(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SimulatedExternalDisputeTemplate.asMerchantFirstPerson(String)」负责主链路中的“as商家首版Person”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static String asMerchantFirstPerson(String position) {
        String firstPerson;
        if (position.startsWith("对方称")) {
            firstPerson = "我们说明" + position.substring("对方称".length());
        } else if (position.startsWith("对方")) {
            firstPerson = "我们" + position.substring("对方".length());
        } else {
            firstPerson = "我们说明：" + position;
        }
        return firstPerson.endsWith("。") ? firstPerson : firstPerson + "。";
    }

    // 所属模块：【案件核心与导入 / 应用编排层】类型「InitiatorPerspective」。
    // 类型职责：定义发起方Perspective跨层传递时使用的不可变数据契约；本类型显式提供 「InitiatorPerspective」。
    // 协作关系：主要由 「SimulatedExternalDisputeTemplate.forInitiator」 使用。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record InitiatorPerspective(
            String requestedResolution,
            BigDecimal requestedAmount,
            String requestedItems,
            String requestReason,
            String originalStatement,
            String respondentAttitude,
            String respondentPosition) {

        // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedExternalDisputeTemplate.InitiatorPerspective.InitiatorPerspective(String,BigDecimal,String,String,String,String,String)」。
        // 具体功能：「SimulatedExternalDisputeTemplate.InitiatorPerspective.InitiatorPerspective(String,BigDecimal,String,String,String,String,String)」：在不可变「InitiatorPerspective」写入组件前校验 「requestedResolution」(String)、「requestedAmount」(BigDecimal)、「requestedItems」(String)、「requestReason」(String)、「originalStatement」(String)、「respondentAttitude」(String)、「respondentPosition」(String)，并通过 「requireText」 做标准化或防御性复制。
        // 上游调用：「SimulatedExternalDisputeTemplate.InitiatorPerspective.InitiatorPerspective(String,BigDecimal,String,String,String,String,String)」的上游创建点包括 「SimulatedExternalDisputeTemplate.forInitiator」。
        // 下游影响：「SimulatedExternalDisputeTemplate.InitiatorPerspective.InitiatorPerspective(String,BigDecimal,String,String,String,String,String)」向下依次触达 「requireText」。
        // 系统意义：「SimulatedExternalDisputeTemplate.InitiatorPerspective.InitiatorPerspective(String,BigDecimal,String,String,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public InitiatorPerspective {
            requireText(requestedResolution, "requestedResolution");
            requireText(requestedItems, "requestedItems");
            requireText(requestReason, "requestReason");
            requireText(originalStatement, "originalStatement");
            requireText(respondentAttitude, "respondentAttitude");
            requireText(respondentPosition, "respondentPosition");
        }
    }
}
