/*
 * 所属模块：共享小法庭。
 * 文件职责：编排庭审终态草案规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「adoptExistingDraftForFinalSealedRound」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adopts the final draft produced by the Temporal C6 activity.
 *
 * <p>This service deliberately has no agent client and no draft creation path. Final adjudication
 * draft generation belongs exclusively to the Temporal final-convergence activity.
 */
// 所属模块：【共享小法庭 / 应用编排层】类型「HearingFinalDraftService」。
// 类型职责：编排庭审终态草案规则、权限校验与事实读写；本类型显式提供 「HearingFinalDraftService」、「adoptExistingDraftForFinalSealedRound」、「completeStateIfNeeded」、「assertFinalRoundSealed」。
// 协作关系：主要由 「HearingRoundService.completeHearing」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingFinalDraftService {

    static final String FINAL_DRAFT_NODE = "C6_DRAFT_GENERATION";

    private final HearingStateRepository stateRepository;
    private final HearingRoundRepository roundRepository;
    private final AdjudicationDraftRepository draftRepository;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalDraftService.HearingFinalDraftService(HearingStateRepository,HearingRoundRepository,AdjudicationDraftRepository)」。
    // 具体功能：「HearingFinalDraftService.HearingFinalDraftService(HearingStateRepository,HearingRoundRepository,AdjudicationDraftRepository)」：通过构造器接收 「stateRepository」(HearingStateRepository)、「roundRepository」(HearingRoundRepository)、「draftRepository」(AdjudicationDraftRepository) 并保存为「HearingFinalDraftService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingFinalDraftService.HearingFinalDraftService(HearingStateRepository,HearingRoundRepository,AdjudicationDraftRepository)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「HearingFinalDraftService.HearingFinalDraftService(HearingStateRepository,HearingRoundRepository,AdjudicationDraftRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingFinalDraftService.HearingFinalDraftService(HearingStateRepository,HearingRoundRepository,AdjudicationDraftRepository)」负责主链路中的“庭审终态草案服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingFinalDraftService(
            HearingStateRepository stateRepository,
            HearingRoundRepository roundRepository,
            AdjudicationDraftRepository draftRepository) {
        this.stateRepository = stateRepository;
        this.roundRepository = roundRepository;
        this.draftRepository = draftRepository;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound(String,int,int,String)」。
    // 具体功能：「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound(String,int,int,String)」：构建adoptExisting草案面向终态已封存轮次：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「draftRepository.findByCaseIdAndDraftVersion」、「roundRepository.findByCaseIdAndRoundNo」、「existing.getId」、「assertFinalRoundSealed」，最终返回「String」。
    // 上游调用：「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound(String,int,int,String)」的上游调用点包括 「HearingRoundService.completeHearing」。
    // 下游影响：「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound(String,int,int,String)」向下依次触达 「draftRepository.findByCaseIdAndDraftVersion」、「roundRepository.findByCaseIdAndRoundNo」、「existing.getId」、「assertFinalRoundSealed」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound(String,int,int,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public String adoptExistingDraftForFinalSealedRound(
            String caseId, int finalRoundNo, int maxStatementRounds, String actorId) {
        int draftVersion = finalRoundNo + 1;
        AdjudicationDraftEntity existing =
                draftRepository
                        .findByCaseIdAndDraftVersion(caseId, draftVersion)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "final adjudication draft is not available"));
        HearingRoundEntity finalRound =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, finalRoundNo)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "final hearing round not found"));
        assertFinalRoundSealed(finalRound, finalRoundNo, maxStatementRounds);
        completeStateIfNeeded(caseId, finalRoundNo, existing, actorId);
        return existing.getId();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalDraftService.completeStateIfNeeded(String,int,AdjudicationDraftEntity,String)」。
    // 具体功能：「HearingFinalDraftService.completeStateIfNeeded(String,int,AdjudicationDraftEntity,String)」：完成状态IfNeeded：先把新状态写入 PostgreSQL 事实表；实际协作者为 「stateRepository.findByCaseId」、「stateRepository.save」、「state.getCompletedAt」、「draft.getConfidence」；处理的关键状态/协议值包括 「{}」、「[]」，最终返回「void」。
    // 上游调用：「HearingFinalDraftService.completeStateIfNeeded(String,int,AdjudicationDraftEntity,String)」的上游调用点包括 「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound」。
    // 下游影响：「HearingFinalDraftService.completeStateIfNeeded(String,int,AdjudicationDraftEntity,String)」向下依次触达 「stateRepository.findByCaseId」、「stateRepository.save」、「state.getCompletedAt」、「draft.getConfidence」。
    // 系统意义：「HearingFinalDraftService.completeStateIfNeeded(String,int,AdjudicationDraftEntity,String)」负责主链路中的“状态IfNeeded”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void completeStateIfNeeded(
            String caseId, int finalRoundNo, AdjudicationDraftEntity draft, String actorId) {
        stateRepository
                .findByCaseId(caseId)
                .ifPresent(
                        state -> {
                            if (state.getCompletedAt() == null) {
                                BigDecimal confidence =
                                        draft.getConfidence() == null
                                                ? BigDecimal.valueOf(0.25)
                                                : draft.getConfidence();
                                state.applyAnalysis(
                                        finalRoundNo,
                                        FINAL_DRAFT_NODE,
                                        confidence,
                                        false,
                                        true,
                                        "{}",
                                        "[]",
                                        "[]",
                                        OffsetDateTime.now(ZoneOffset.UTC),
                                        actorId);
                                state.complete(true, actorId);
                                stateRepository.save(state);
                            }
                        });
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalDraftService.assertFinalRoundSealed(HearingRoundEntity,int,int)」。
    // 具体功能：「HearingFinalDraftService.assertFinalRoundSealed(HearingRoundEntity,int,int)」：断言终态轮次已封存；实际协作者为 「finalRound.getRoundStatus」、「finalRound.getClosedAt」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「HearingFinalDraftService.assertFinalRoundSealed(HearingRoundEntity,int,int)」的上游调用点包括 「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound」。
    // 下游影响：「HearingFinalDraftService.assertFinalRoundSealed(HearingRoundEntity,int,int)」向下依次触达 「finalRound.getRoundStatus」、「finalRound.getClosedAt」。
    // 系统意义：「HearingFinalDraftService.assertFinalRoundSealed(HearingRoundEntity,int,int)」在“终态轮次已封存”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void assertFinalRoundSealed(
            HearingRoundEntity finalRound, int finalRoundNo, int maxStatementRounds) {
        boolean terminalStatus =
                finalRound.getRoundStatus() == HearingRoundStatus.COMPLETED
                        || finalRound.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED;
        if (!terminalStatus
                || finalRound.getClosedAt() == null
                || finalRoundNo < maxStatementRounds) {
            throw new IllegalStateException("final hearing round is not sealed");
        }
    }
}
