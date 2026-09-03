/*
 * 所属模块：听证准入路由。
 * 文件职责：定义庭审路由结果跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；依据争点、证据充分度和风险把案件分入三种最终听证路线。
 * 关键边界：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
 */
package com.example.dispute.routing.domain;

import java.util.Objects;

/** Immutable routing evidence persisted before a workflow path is started. */
// 所属模块：【听证准入路由 / 领域模型层】类型「HearingRoutingOutcome」。
// 类型职责：定义庭审路由结果跨层传递时使用的不可变数据契约；本类型显式提供 「HearingRoutingOutcome」。
// 协作关系：主要由 「AdmissibilityHearingRouter.decide」 使用。
// 边界意义：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingRoutingOutcome(
        HearingRoute route,
        String reasonCode,
        boolean terminalInDisputeSystem,
        boolean requiresAdditionalEvidence,
        boolean requiresDeliberation) {

    // 所属模块：【听证准入路由 / 领域模型层】「HearingRoutingOutcome.HearingRoutingOutcome(HearingRoute,String,boolean,boolean,boolean)」。
    // 具体功能：「HearingRoutingOutcome.HearingRoutingOutcome(HearingRoute,String,boolean,boolean,boolean)」：在不可变「HearingRoutingOutcome」写入组件前校验 「route」(HearingRoute)、「reasonCode」(String)、「terminalInDisputeSystem」(boolean)、「requiresAdditionalEvidence」(boolean)、「requiresDeliberation」(boolean)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「HearingRoutingOutcome.HearingRoutingOutcome(HearingRoute,String,boolean,boolean,boolean)」的上游创建点包括 「AdmissibilityHearingRouter.decide」。
    // 下游影响：「HearingRoutingOutcome.HearingRoutingOutcome(HearingRoute,String,boolean,boolean,boolean)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「HearingRoutingOutcome.HearingRoutingOutcome(HearingRoute,String,boolean,boolean,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public HearingRoutingOutcome {
        Objects.requireNonNull(route, "route must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (route == HearingRoute.TRANSFERRED && !terminalInDisputeSystem) {
            throw new IllegalArgumentException(
                    "TRANSFERRED must be terminal in the dispute system");
        }
    }
}
