/*
 * 所属模块：共享小法庭。
 * 文件职责：定义庭审法庭Agent跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import java.util.List;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingCourtAgentResult」。
// 类型职责：定义庭审法庭Agent跨层传递时使用的不可变数据契约；本类型显式提供 「HearingCourtAgentResult」、「HearingCourtAgentResult」、「blankToDefault」。
// 协作关系：主要由 「HearingCourtOrchestrator.fallback」、「RestClientHearingCourtAgentClient.generateRoundTurn」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」、「HearingCourtOrchestratorTest.afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingCourtAgentResult(
        String speakerRole,
        String messageText,
        String roundSummary,
        List<String> questionsForUser,
        List<String> questionsForMerchant,
        String courtEventType,
        int roundNo,
        Integer nextRoundNo,
        boolean finalDraftRequired,
        List<String> reviewFocusSignal,
        String promptVersion,
        String model) {

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,List,String,String)」。
    // 具体功能：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,List,String,String)」：在不可变「HearingCourtAgentResult」写入组件前校验 「speakerRole」(String)、「messageText」(String)、「roundSummary」(String)、「questionsForUser」(List)、「questionsForMerchant」(List)、「courtEventType」(String)、「roundNo」(int)、「nextRoundNo」(Integer)、「finalDraftRequired」(boolean)、「reviewFocusSignal」(List)、「promptVersion」(String)、「model」(String)，并通过 「blankToDefault」 做标准化或防御性复制。
    // 上游调用：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,List,String,String)」的上游创建点包括 「HearingCourtOrchestrator.fallback」、「RestClientHearingCourtAgentClient.generateRoundTurn」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」。
    // 下游影响：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,List,String,String)」向下依次触达 「blankToDefault」。
    // 系统意义：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,List,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public HearingCourtAgentResult {
        speakerRole = blankToDefault(speakerRole, "JUDGE");
        messageText = blankToDefault(messageText, "本轮庭审材料已封存。");
        roundSummary = blankToDefault(roundSummary, "");
        questionsForUser = questionsForUser == null ? List.of() : List.copyOf(questionsForUser);
        questionsForMerchant =
                questionsForMerchant == null ? List.of() : List.copyOf(questionsForMerchant);
        reviewFocusSignal =
                reviewFocusSignal == null ? List.of() : List.copyOf(reviewFocusSignal);
        courtEventType =
                blankToDefault(
                        courtEventType,
                        finalDraftRequired
                                ? "FINAL_DRAFT_REQUIRED"
                                : "JUDGE_NEXT_QUESTIONS_READY");
        promptVersion = blankToDefault(promptVersion, "hearing-round-turn-unknown");
        model = blankToDefault(model, "unknown");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,String,String)」。
    // 具体功能：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,String,String)」：使用 「speakerRole」(String)、「messageText」(String)、「roundSummary」(String)、「questionsForUser」(List)、「questionsForMerchant」(List)、「courtEventType」(String)、「roundNo」(int)、「nextRoundNo」(Integer)、「finalDraftRequired」(boolean)、「promptVersion」(String)、「model」(String) 初始化「HearingCourtAgentResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,String,String)」的上游创建点包括 「HearingCourtOrchestrator.fallback」、「RestClientHearingCourtAgentClient.generateRoundTurn」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」。
    // 下游影响：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCourtAgentResult.HearingCourtAgentResult(String,String,String,List,List,String,int,Integer,boolean,String,String)」负责主链路中的“庭审法庭Agent结果”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingCourtAgentResult(
            String speakerRole,
            String messageText,
            String roundSummary,
            List<String> questionsForUser,
            List<String> questionsForMerchant,
            String courtEventType,
            int roundNo,
            Integer nextRoundNo,
            boolean finalDraftRequired,
            String promptVersion,
            String model) {
        this(
                speakerRole,
                messageText,
                roundSummary,
                questionsForUser,
                questionsForMerchant,
                courtEventType,
                roundNo,
                nextRoundNo,
                finalDraftRequired,
                List.of(),
                promptVersion,
                model);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtAgentResult.blankToDefault(String,String)」。
    // 具体功能：「HearingCourtAgentResult.blankToDefault(String,String)」：判断空白值默认，最终返回「String」。
    // 上游调用：「HearingCourtAgentResult.blankToDefault(String,String)」的上游调用点包括 「HearingCourtAgentResult.HearingCourtAgentResult」。
    // 下游影响：「HearingCourtAgentResult.blankToDefault(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtAgentResult.blankToDefault(String,String)」负责主链路中的“默认”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
