/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：限定证据提交状态允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.domain;

// 所属模块：【证据与版本化卷宗 / 领域模型层】类型「EvidenceSubmissionStatus」。
// 类型职责：限定证据提交状态允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum EvidenceSubmissionStatus {
    PENDING_SUBMISSION,
    SUBMITTED,
    VOIDED
}
