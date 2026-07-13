/*
 * 所属模块：共享小法庭。
 * 文件职责：定义AgentA2A跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import java.util.Map;

// 所属模块：【共享小法庭 / 应用编排层】类型「AgentA2ACommand」。
// 类型职责：定义AgentA2A跨层传递时使用的不可变数据契约；本类型显式提供 「AgentA2ACommand」。
// 协作关系：主要由 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AgentA2ACommand(
        String caseId,
        int roundNo,
        String fromAgent,
        String toAgent,
        String messageType,
        Map<String, Object> inputRefs,
        Map<String, Object> payload,
        String visibility,
        String agentRunId) {

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2ACommand.AgentA2ACommand(String,int,String,String,String,Map,Map,String,String)」。
    // 具体功能：「AgentA2ACommand.AgentA2ACommand(String,int,String,String,String,Map,Map,String,String)」：在不可变「AgentA2ACommand」写入组件前校验 「caseId」(String)、「roundNo」(int)、「fromAgent」(String)、「toAgent」(String)、「messageType」(String)、「inputRefs」(Map)、「payload」(Map)、「visibility」(String)、「agentRunId」(String)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「AgentA2ACommand.AgentA2ACommand(String,int,String,String,String,Map,Map,String,String)」的上游创建点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」。
    // 下游影响：「AgentA2ACommand.AgentA2ACommand(String,int,String,String,String,Map,Map,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentA2ACommand.AgentA2ACommand(String,int,String,String,String,Map,Map,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public AgentA2ACommand {
        if (roundNo < 1) {
            throw new IllegalArgumentException("roundNo must be positive");
        }
        inputRefs = inputRefs == null ? Map.of() : Map.copyOf(inputRefs);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
