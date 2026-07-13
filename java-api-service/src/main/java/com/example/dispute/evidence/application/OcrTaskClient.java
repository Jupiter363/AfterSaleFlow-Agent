/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义OCR任务外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「createParseTask」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.fasterxml.jackson.annotation.JsonProperty;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「OcrTaskClient」。
// 类型职责：定义OCR任务外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「createParseTask」。
// 协作关系：主要由 「EvidenceApplicationService.triggerNonBlockingIntegrations」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface OcrTaskClient {

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「OcrTaskClient.createParseTask(ParseTask)」。
    // 具体功能：「OcrTaskClient.createParseTask(ParseTask)」：定义「OcrTaskClient」端口方法：接收 「task」(ParseTask)，返回「void」；具体副作用由 「RestClientOcrTaskClient」 承担。
    // 上游调用：「OcrTaskClient.createParseTask(ParseTask)」的上游调用点包括 「EvidenceApplicationService.triggerNonBlockingIntegrations」。
    // 下游影响：「OcrTaskClient.createParseTask(ParseTask)」的下游由 「RestClientOcrTaskClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「OcrTaskClient.createParseTask(ParseTask)」负责主链路中的“解析任务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void createParseTask(ParseTask task);

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「ParseTask」。
    // 类型职责：定义解析任务跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    record ParseTask(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("case_id") String caseId,
            String bucket,
            @JsonProperty("object_key") String objectKey,
            @JsonProperty("content_type") String contentType) {}
}
