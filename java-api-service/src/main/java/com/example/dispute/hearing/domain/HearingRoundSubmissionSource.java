/*
 * 所属模块：共享小法庭。
 * 文件职责：限定庭审轮次提交来源允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.domain;

// 所属模块：【共享小法庭 / 领域模型层】类型「HearingRoundSubmissionSource」。
// 类型职责：限定庭审轮次提交来源允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum HearingRoundSubmissionSource {
    PARTY_ACTION,
    AUTO_TIMEOUT
}
