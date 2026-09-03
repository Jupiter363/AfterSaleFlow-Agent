/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据存储的模块端口，隔离调用方与具体实现。
 * 业务链路：核心入口/契约为 「storeOriginal」、「loadOriginal」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceStorage」。
// 类型职责：定义证据存储的模块端口，隔离调用方与具体实现；本类型显式提供 「storeOriginal」、「loadOriginal」。
// 协作关系：主要由 「EvidenceApplicationService.loadContent」、「EvidenceApplicationService.upload」、「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」、「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidenceStorage {

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceStorage.storeOriginal(String,String,String,String,byte[])」。
    // 具体功能：「EvidenceStorage.storeOriginal(String,String,String,String,byte[])」：定义「EvidenceStorage」端口方法：接收 「caseId」(String)、「evidenceId」(String)、「filename」(String)、「contentType」(String)、「content」(byte[])，返回「StoredObject」；具体副作用由 「MinioEvidenceStorage」 承担。
    // 上游调用：「EvidenceStorage.storeOriginal(String,String,String,String,byte[])」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」、「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial」。
    // 下游影响：「EvidenceStorage.storeOriginal(String,String,String,String,byte[])」的下游由 「MinioEvidenceStorage」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceStorage.storeOriginal(String,String,String,String,byte[])」负责主链路中的“原件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    StoredObject storeOriginal(
            String caseId,
            String evidenceId,
            String filename,
            String contentType,
            byte[] content);

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceStorage.loadOriginal(String,String)」。
    // 具体功能：「EvidenceStorage.loadOriginal(String,String)」：定义「EvidenceStorage」端口方法：接收 「bucket」(String)、「objectKey」(String)，返回「byte[]」；具体副作用由 「MinioEvidenceStorage」 承担。
    // 上游调用：「EvidenceStorage.loadOriginal(String,String)」的上游调用点包括 「EvidenceApplicationService.loadContent」、「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」、「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization」。
    // 下游影响：「EvidenceStorage.loadOriginal(String,String)」的下游由 「MinioEvidenceStorage」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceStorage.loadOriginal(String,String)」负责主链路中的“原件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    byte[] loadOriginal(String bucket, String objectKey);

    // 所属模块：【证据与版本化卷宗 / 应用编排层】类型「StoredObject」。
    // 类型职责：定义Stored对象跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    record StoredObject(String bucket, String objectKey) {}
}
