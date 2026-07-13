/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据完成确认状态跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import java.time.OffsetDateTime;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceCompletionStatusView」。
// 类型职责：定义证据完成确认状态跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceCompletionStatusView(
        String caseId,
        int dossierVersion,
        boolean userCompleted,
        boolean merchantCompleted,
        boolean sealed,
        String nextRoom,
        OffsetDateTime nextDeadlineAt) {}
