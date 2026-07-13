/*
 * 所属模块：听证准入路由。
 * 文件职责：限定庭审路由允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；依据争点、证据充分度和风险把案件分入三种最终听证路线。
 * 关键边界：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
 */
package com.example.dispute.routing.domain;

/** The only three routing results permitted inside the final dispute system. */
// 所属模块：【听证准入路由 / 领域模型层】类型「HearingRoute」。
// 类型职责：限定庭审路由允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum HearingRoute {
    TRANSFERRED,
    SIMPLE_HEARING,
    FULL_HEARING
}
