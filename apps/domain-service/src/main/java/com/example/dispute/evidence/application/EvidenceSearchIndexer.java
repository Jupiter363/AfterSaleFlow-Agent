/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据检索索引的模块端口，隔离调用方与具体实现。
 * 业务链路：核心入口/契约为 「indexMetadata」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceSearchIndexer」。
// 类型职责：定义证据检索索引的模块端口，隔离调用方与具体实现；本类型显式提供 「indexMetadata」。
// 协作关系：主要由 「EvidenceApplicationService.triggerNonBlockingIntegrations」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceSearchIndexer {
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSearchIndexer.indexMetadata(EvidenceView)」。
    // 具体功能：「EvidenceSearchIndexer.indexMetadata(EvidenceView)」：定义「EvidenceSearchIndexer」端口方法：接收 「evidence」(EvidenceView)，返回「void」；具体副作用由 「RestClientEvidenceSearchIndexer」 承担。
    // 上游调用：「EvidenceSearchIndexer.indexMetadata(EvidenceView)」的上游调用点包括 「EvidenceApplicationService.triggerNonBlockingIntegrations」。
    // 下游影响：「EvidenceSearchIndexer.indexMetadata(EvidenceView)」的下游由 「RestClientEvidenceSearchIndexer」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceSearchIndexer.indexMetadata(EvidenceView)」负责主链路中的“元数据”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void indexMetadata(EvidenceView evidence);
}
