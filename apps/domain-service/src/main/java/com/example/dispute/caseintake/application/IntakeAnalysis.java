/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：定义接待Analysis跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

// 所属模块：【案件受理兼容链路 / 应用编排层】类型「IntakeAnalysis」。
// 类型职责：定义接待Analysis跨层传递时使用的不可变数据契约；本类型显式提供 「IntakeAnalysis」。
// 协作关系：主要由 「CaseApplicationService.initialIntakeShell」、「RestClientAgentServiceClient.analyze」 使用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeAnalysis(
        String caseType,
        String disputeType,
        RiskLevel riskLevel,
        boolean potentialDispute,
        List<String> missingSlots,
        String title,
        String normalizedDescription) {

    // 所属模块：【案件受理兼容链路 / 应用编排层】「IntakeAnalysis.IntakeAnalysis(String,String,RiskLevel,boolean,List,String,String)」。
    // 具体功能：「IntakeAnalysis.IntakeAnalysis(String,String,RiskLevel,boolean,List,String,String)」：在不可变「IntakeAnalysis」写入组件前校验 「caseType」(String)、「disputeType」(String)、「riskLevel」(RiskLevel)、「potentialDispute」(boolean)、「missingSlots」(List)、「title」(String)、「normalizedDescription」(String)，并统一规范 record 组件值。
    // 上游调用：「IntakeAnalysis.IntakeAnalysis(String,String,RiskLevel,boolean,List,String,String)」的上游创建点包括 「CaseApplicationService.initialIntakeShell」、「RestClientAgentServiceClient.analyze」。
    // 下游影响：「IntakeAnalysis.IntakeAnalysis(String,String,RiskLevel,boolean,List,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeAnalysis.IntakeAnalysis(String,String,RiskLevel,boolean,List,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public IntakeAnalysis {
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
