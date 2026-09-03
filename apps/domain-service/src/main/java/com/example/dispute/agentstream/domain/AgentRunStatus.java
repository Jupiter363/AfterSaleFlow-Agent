/*
 * 所属模块：Agent 流式运行。
 * 文件职责：限定Agent运行状态允许出现的状态值。
 * 业务链路：核心入口/契约为 「terminal」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.domain;

// 所属模块：【Agent 流式运行 / 领域模型层】类型「AgentRunStatus」。
// 类型职责：限定Agent运行状态允许出现的状态值；本类型显式提供 「terminal」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum AgentRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    // 所属模块：【Agent 流式运行 / 领域模型层】「AgentRunStatus.terminal()」。
    // 具体功能：「AgentRunStatus.terminal()」：判断当前运行状态是否已经进入 COMPLETED、FAILED 或 CANCELLED；终态运行不会再次被 Worker 领取或恢复任务改写，最终返回「boolean」。
    // 上游调用：「AgentRunStatus.terminal()」由使用「AgentRunStatus」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunStatus.terminal()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunStatus.terminal()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
