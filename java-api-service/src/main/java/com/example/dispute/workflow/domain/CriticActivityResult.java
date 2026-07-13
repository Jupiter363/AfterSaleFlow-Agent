/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义评审角色跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.List;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「CriticActivityResult」。
// 类型职责：定义评审角色跨层传递时使用的不可变数据契约；本类型显式提供 「CriticActivityResult」、「CriticActivityResult」、「defaultScore」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.runCritic」、「RecordingActivities.runCritic」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CriticActivityResult(
        String critic,
        String status,
        String severity,
        List<String> blockingIssues,
        String frozenInputFingerprint,
        int score) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「CriticActivityResult.CriticActivityResult(String,String,String,List,String,int)」。
    // 具体功能：「CriticActivityResult.CriticActivityResult(String,String,String,List,String,int)」：在不可变「CriticActivityResult」写入组件前校验 「critic」(String)、「status」(String)、「severity」(String)、「blockingIssues」(List)、「frozenInputFingerprint」(String)、「score」(int)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「CriticActivityResult.CriticActivityResult(String,String,String,List,String,int)」的上游创建点包括 「FinalWorkflowActivitiesAdapter.runCritic」、「RecordingActivities.runCritic」。
    // 下游影响：「CriticActivityResult.CriticActivityResult(String,String,String,List,String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CriticActivityResult.CriticActivityResult(String,String,String,List,String,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public CriticActivityResult {
        blockingIssues =
                blockingIssues == null
                        ? List.of()
                        : List.copyOf(blockingIssues);
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("critic score must be between 0 and 100");
        }
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「CriticActivityResult.CriticActivityResult(String,String,String,List,String)」。
    // 具体功能：「CriticActivityResult.CriticActivityResult(String,String,String,List,String)」：使用 「critic」(String)、「status」(String)、「severity」(String)、「blockingIssues」(List)、「frozenInputFingerprint」(String) 初始化「CriticActivityResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「CriticActivityResult.CriticActivityResult(String,String,String,List,String)」的上游创建点包括 「FinalWorkflowActivitiesAdapter.runCritic」、「RecordingActivities.runCritic」。
    // 下游影响：「CriticActivityResult.CriticActivityResult(String,String,String,List,String)」向下依次触达 「defaultScore」。
    // 系统意义：「CriticActivityResult.CriticActivityResult(String,String,String,List,String)」负责主链路中的“评审角色活动结果”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CriticActivityResult(
            String critic,
            String status,
            String severity,
            List<String> blockingIssues,
            String frozenInputFingerprint) {
        this(
                critic,
                status,
                severity,
                blockingIssues,
                frozenInputFingerprint,
                defaultScore(status, severity));
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「CriticActivityResult.defaultScore(String,String)」。
    // 具体功能：「CriticActivityResult.defaultScore(String,String)」：更新默认分数：先更新内部状态 「severity」；处理的关键状态/协议值包括 「COMPLETED」、「BLOCKER」、「HIGH」，最终返回「int」。
    // 上游调用：「CriticActivityResult.defaultScore(String,String)」的上游调用点包括 「CriticActivityResult.CriticActivityResult」。
    // 下游影响：「CriticActivityResult.defaultScore(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「CriticActivityResult.defaultScore(String,String)」负责主链路中的“默认分数”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static int defaultScore(String status, String severity) {
        if (!"COMPLETED".equals(status)) {
            return 0;
        }
        return switch (severity == null ? "" : severity) {
            case "BLOCKER" -> 0;
            case "HIGH" -> 79;
            default -> 100;
        };
    }
}
