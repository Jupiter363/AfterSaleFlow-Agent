/*
 * 所属模块：平台人工终审。
 * 文件职责：定义审核审核包Versions跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.domain;

// 所属模块：【平台人工终审 / 领域模型层】类型「ReviewPacketVersions」。
// 类型职责：定义审核审核包Versions跨层传递时使用的不可变数据契约；本类型显式提供 「ReviewPacketVersions」、「requireText」。
// 协作关系：主要由 「ReviewApplicationService.createForWorkflow」、「ReviewPacketEntity.create」、「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview」、「PostReviewOrchestrationServiceIntegrationTest.seed」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ReviewPacketVersions(
        long caseVersion,
        int dossierVersion,
        int issueVersion,
        int adjudicationDraftVersion,
        int deliberationReportVersion,
        int remedyPlanVersion,
        String rulesetVersion,
        String promptVersion,
        String skillVersion,
        String profileVersion) {

    // 所属模块：【平台人工终审 / 领域模型层】「ReviewPacketVersions.ReviewPacketVersions(long,int,int,int,int,int,String,String,String,String)」。
    // 具体功能：「ReviewPacketVersions.ReviewPacketVersions(long,int,int,int,int,int,String,String,String,String)」：在不可变「ReviewPacketVersions」写入组件前校验 「caseVersion」(long)、「dossierVersion」(int)、「issueVersion」(int)、「adjudicationDraftVersion」(int)、「deliberationReportVersion」(int)、「remedyPlanVersion」(int)、「rulesetVersion」(String)、「promptVersion」(String)、「skillVersion」(String)、「profileVersion」(String)，非法输入会抛出 「IllegalArgumentException」；并通过 「requireText」 做标准化或防御性复制。
    // 上游调用：「ReviewPacketVersions.ReviewPacketVersions(long,int,int,int,int,int,String,String,String,String)」的上游创建点包括 「ReviewPacketEntity.create」、「ReviewApplicationService.createForWorkflow」、「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」、「ToolExecutorServiceIntegrationTest.seed」。
    // 下游影响：「ReviewPacketVersions.ReviewPacketVersions(long,int,int,int,int,int,String,String,String,String)」向下依次触达 「requireText」。
    // 系统意义：「ReviewPacketVersions.ReviewPacketVersions(long,int,int,int,int,int,String,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ReviewPacketVersions {
        if (caseVersion < 1
                || dossierVersion < 1
                || issueVersion < 1
                || adjudicationDraftVersion < 1
                || deliberationReportVersion < 0
                || remedyPlanVersion < 1) {
            throw new IllegalArgumentException(
                    "review packet versions must identify frozen artifacts");
        }
        requireText(rulesetVersion, "rulesetVersion");
        requireText(promptVersion, "promptVersion");
        requireText(skillVersion, "skillVersion");
        requireText(profileVersion, "profileVersion");
    }

    // 所属模块：【平台人工终审 / 领域模型层】「ReviewPacketVersions.requireText(String,String)」。
    // 具体功能：「ReviewPacketVersions.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「ReviewPacketVersions.requireText(String,String)」的上游调用点包括 「ReviewPacketVersions.ReviewPacketVersions」。
    // 下游影响：「ReviewPacketVersions.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewPacketVersions.requireText(String,String)」在“文本”进入下游前阻断非法状态；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
