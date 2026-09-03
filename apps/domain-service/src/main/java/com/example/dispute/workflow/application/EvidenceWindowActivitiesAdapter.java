/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：在 Temporal Activity 边界执行证据Window所需的数据库、Agent 或工具副作用。
 * 业务链路：核心入口/契约为 「warn」、「expire」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.workflow.temporal.EvidenceWindowActivities;
import org.springframework.stereotype.Component;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「EvidenceWindowActivitiesAdapter」。
// 类型职责：在 Temporal Activity 边界执行证据Window所需的数据库、Agent 或工具副作用；本类型显式提供 「EvidenceWindowActivitiesAdapter」、「warn」、「expire」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class EvidenceWindowActivitiesAdapter implements EvidenceWindowActivities {

    private final EvidenceCompletionService completionService;

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowActivitiesAdapter.EvidenceWindowActivitiesAdapter(EvidenceCompletionService)」。
    // 具体功能：「EvidenceWindowActivitiesAdapter.EvidenceWindowActivitiesAdapter(EvidenceCompletionService)」：通过构造器接收 「completionService」(EvidenceCompletionService) 并保存为「EvidenceWindowActivitiesAdapter」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceWindowActivitiesAdapter.EvidenceWindowActivitiesAdapter(EvidenceCompletionService)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「EvidenceWindowActivitiesAdapter.EvidenceWindowActivitiesAdapter(EvidenceCompletionService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceWindowActivitiesAdapter.EvidenceWindowActivitiesAdapter(EvidenceCompletionService)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceWindowActivitiesAdapter(EvidenceCompletionService completionService) {
        this.completionService = completionService;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowActivitiesAdapter.warn(String)」。
    // 具体功能：「EvidenceWindowActivitiesAdapter.warn(String)」：发送临期提醒证据Window；实际协作者为 「completionService.warnDeadline」，最终返回「void」。
    // 上游调用：「EvidenceWindowActivitiesAdapter.warn(String)」由使用「EvidenceWindowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceWindowActivitiesAdapter.warn(String)」向下依次触达 「completionService.warnDeadline」。
    // 系统意义：「EvidenceWindowActivitiesAdapter.warn(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void warn(String caseId) {
        completionService.warnDeadline(caseId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowActivitiesAdapter.expire(String)」。
    // 具体功能：「EvidenceWindowActivitiesAdapter.expire(String)」：标记过期证据Window；实际协作者为 「completionService.expire」，最终返回「void」。
    // 上游调用：「EvidenceWindowActivitiesAdapter.expire(String)」由使用「EvidenceWindowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceWindowActivitiesAdapter.expire(String)」向下依次触达 「completionService.expire」。
    // 系统意义：「EvidenceWindowActivitiesAdapter.expire(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void expire(String caseId) {
        completionService.expire(caseId);
    }
}
