/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据核验跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceVerificationCommand」。
// 类型职责：定义证据核验跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceVerificationCommand」。
// 协作关系：主要由 「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceVerificationCommand(
        boolean hashValid,
        boolean signatureValid,
        boolean sourceTrusted,
        boolean mimeValid,
        boolean sizeAllowed,
        boolean duplicate,
        boolean agentFlagsConflict,
        String agentFindingsJson) {

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationCommand.EvidenceVerificationCommand(boolean,boolean,boolean,boolean,boolean,boolean,boolean,String)」。
    // 具体功能：「EvidenceVerificationCommand.EvidenceVerificationCommand(boolean,boolean,boolean,boolean,boolean,boolean,boolean,String)」：在不可变「EvidenceVerificationCommand」写入组件前校验 「hashValid」(boolean)、「signatureValid」(boolean)、「sourceTrusted」(boolean)、「mimeValid」(boolean)、「sizeAllowed」(boolean)、「duplicate」(boolean)、「agentFlagsConflict」(boolean)、「agentFindingsJson」(String)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「EvidenceVerificationCommand.EvidenceVerificationCommand(boolean,boolean,boolean,boolean,boolean,boolean,boolean,String)」的上游创建点包括 「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」。
    // 下游影响：「EvidenceVerificationCommand.EvidenceVerificationCommand(boolean,boolean,boolean,boolean,boolean,boolean,boolean,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceVerificationCommand.EvidenceVerificationCommand(boolean,boolean,boolean,boolean,boolean,boolean,boolean,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceVerificationCommand {
        if (agentFindingsJson == null || agentFindingsJson.isBlank()) {
            throw new IllegalArgumentException("agentFindingsJson must not be blank");
        }
    }
}
