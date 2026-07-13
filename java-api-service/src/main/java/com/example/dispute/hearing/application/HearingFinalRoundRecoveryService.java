/*
 * 所属模块：共享小法庭。
 * 文件职责：编排庭审终态轮次恢复规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「recoverFinalRoundsWithoutDraft」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingFinalRoundRecoveryService」。
// 类型职责：编排庭审终态轮次恢复规则、权限校验与事实读写；本类型显式提供 「HearingFinalRoundRecoveryService」、「recoverFinalRoundsWithoutDraft」。
// 协作关系：主要由 「HearingOutcomeRecoveryScheduler.recover」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」、「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingFinalRoundRecoveryService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingFinalRoundRecoveryService.class);
    private static final List<HearingRoundStatus> SEALED_STATUSES =
            List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED);

    private final HearingRoundRepository roundRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingCourtOrchestrator courtOrchestrator;
    private final HearingWorkflowCoordinator workflowCoordinator;
    private final AgentRunCoordinator agentRunCoordinator;
    private final DisputeProperties disputeProperties;
    private final AtomicReference<RecoveryCursor> cursor =
            new AtomicReference<>(RecoveryCursor.start());

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalRoundRecoveryService.HearingFinalRoundRecoveryService(HearingRoundRepository,AdjudicationDraftRepository,HearingCourtOrchestrator,HearingWorkflowCoordinator,DisputeProperties)」。
    // 具体功能：「HearingFinalRoundRecoveryService.HearingFinalRoundRecoveryService(HearingRoundRepository,AdjudicationDraftRepository,HearingCourtOrchestrator,HearingWorkflowCoordinator,DisputeProperties)」：通过构造器接收 「roundRepository」(HearingRoundRepository)、「draftRepository」(AdjudicationDraftRepository)、「courtOrchestrator」(HearingCourtOrchestrator)、「workflowCoordinator」(HearingWorkflowCoordinator)、「disputeProperties」(DisputeProperties) 并保存为「HearingFinalRoundRecoveryService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingFinalRoundRecoveryService.HearingFinalRoundRecoveryService(HearingRoundRepository,AdjudicationDraftRepository,HearingCourtOrchestrator,HearingWorkflowCoordinator,DisputeProperties)」的上游创建点包括 「HearingFinalRoundRecoveryServiceTest.setUp」。
    // 下游影响：「HearingFinalRoundRecoveryService.HearingFinalRoundRecoveryService(HearingRoundRepository,AdjudicationDraftRepository,HearingCourtOrchestrator,HearingWorkflowCoordinator,DisputeProperties)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingFinalRoundRecoveryService.HearingFinalRoundRecoveryService(HearingRoundRepository,AdjudicationDraftRepository,HearingCourtOrchestrator,HearingWorkflowCoordinator,DisputeProperties)」负责主链路中的“庭审终态轮次恢复服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingFinalRoundRecoveryService(
            HearingRoundRepository roundRepository,
            AdjudicationDraftRepository draftRepository,
            HearingCourtOrchestrator courtOrchestrator,
            HearingWorkflowCoordinator workflowCoordinator,
            AgentRunCoordinator agentRunCoordinator,
            DisputeProperties disputeProperties) {
        this.roundRepository = roundRepository;
        this.draftRepository = draftRepository;
        this.courtOrchestrator = courtOrchestrator;
        this.workflowCoordinator = workflowCoordinator;
        this.agentRunCoordinator = agentRunCoordinator;
        this.disputeProperties = disputeProperties;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft(int)」。
    // 具体功能：「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft(int)」：恢复终态Rounds缺少草案；实际协作者为 「roundRepository.findFinalRoundsWithoutDraft」、「roundRepository.findFinalRoundsWithoutDraftAfter」、「draftRepository.findByCaseIdAndDraftVersion」、「workflowCoordinator.roundCompletedNow」；处理的关键状态/协议值包括 「TRACE_HEARING_FINAL_RECOVERY_」，最终返回「int」。
    // 上游调用：「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft(int)」的上游调用点包括 「HearingOutcomeRecoveryScheduler.recover」、「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」。
    // 下游影响：「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft(int)」向下依次触达 「roundRepository.findFinalRoundsWithoutDraft」、「roundRepository.findFinalRoundsWithoutDraftAfter」、「draftRepository.findByCaseIdAndDraftVersion」、「workflowCoordinator.roundCompletedNow」；计算结果以「int」交给调用方。
    // 系统意义：「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft(int)」负责主链路中的“终态Rounds缺少草案”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int recoverFinalRoundsWithoutDraft(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int finalRoundNo = disputeProperties.maxHearingRounds();
        int recovered = 0;
        int scanned = 0;
        RecoveryCursor current = cursor.get();
        boolean startedAtBeginning = current.isStart();
        boolean wrapped = false;
        Set<String> seenRoundIds = new HashSet<>();
        while (scanned < limit) {
            int pageSize = limit - scanned;
            var candidates =
                    current.isStart()
                            ? roundRepository.findFinalRoundsWithoutDraft(
                                    finalRoundNo,
                                    finalRoundNo + 1,
                                    SEALED_STATUSES,
                                    PageRequest.of(0, pageSize))
                            : roundRepository.findFinalRoundsWithoutDraftAfter(
                                    finalRoundNo,
                                    finalRoundNo + 1,
                                    SEALED_STATUSES,
                                    current.closedAt(),
                                    current.roundId(),
                                    PageRequest.of(0, pageSize));
            if (candidates.isEmpty()) {
                if (!current.isStart() && !wrapped) {
                    current = RecoveryCursor.start();
                    cursor.set(current);
                    wrapped = true;
                    continue;
                }
                break;
            }
            for (var round : candidates) {
                current = RecoveryCursor.after(round);
                cursor.set(current);
                scanned++;
                if (!seenRoundIds.add(round.getId())) {
                    if (scanned >= limit) {
                        break;
                    }
                    continue;
                }
                String caseId = round.getCaseId();
                if (draftRepository
                        .findByCaseIdAndDraftVersion(caseId, finalRoundNo + 1)
                        .isPresent()) {
                    continue;
                }
                try {
                    courtOrchestrator.afterRoundClosed(
                            caseId,
                            finalRoundNo,
                            true,
                            "TRACE_HEARING_FINAL_RECOVERY_" + finalRoundNo);
                    if (!courtOrchestrator.hasCompleteFormalJuryReport(
                            caseId, finalRoundNo)) {
                        LOGGER.warn(
                            "Skipping final hearing signal because formal jury report is missing: case_id={}, round_no={}",
                            caseId,
                                finalRoundNo);
                        continue;
                    }
                    boolean converging =
                            workflowCoordinator.roundCompletedNow(
                                    caseId, finalRoundNo, false);
                    if (!converging
                            && agentRunCoordinator.hasRestartableFinalConvergenceFailure(
                                    caseId,
                                    hearingAnalysisKey(caseId, finalRoundNo))) {
                        converging =
                                workflowCoordinator.restartFinalConvergenceNow(
                                        caseId,
                                        round.getDossierVersion(),
                                        finalRoundNo);
                    }
                    if (converging) {
                        recovered++;
                    }
                } catch (RuntimeException failure) {
                    LOGGER.warn(
                            "Failed to recover final hearing convergence: case_id={}, round_no={}",
                            caseId,
                            finalRoundNo,
                            failure);
                }
                if (scanned >= limit) {
                    break;
                }
            }
            if (scanned >= limit) {
                break;
            }
            if (candidates.size() < pageSize) {
                if (!startedAtBeginning && !wrapped) {
                    current = RecoveryCursor.start();
                    cursor.set(current);
                    wrapped = true;
                    continue;
                }
                break;
            }
        }
        return recovered;
    }

    private static String hearingAnalysisKey(String caseId, int roundNo) {
        return "hearing-analysis:" + caseId + ":" + roundNo + ":final";
    }

    // 所属模块：【共享小法庭 / 应用编排层】类型「RecoveryCursor」。
    // 类型职责：定义恢复Cursor跨层传递时使用的不可变数据契约；本类型显式提供 「start」、「after」、「isStart」。
    // 协作关系：主要由 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」 使用。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record RecoveryCursor(Instant closedAt, String roundId) {

        // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalRoundRecoveryService.RecoveryCursor.start()」。
        // 具体功能：「HearingFinalRoundRecoveryService.RecoveryCursor.start()」：启动恢复Cursor，最终返回「RecoveryCursor」。
        // 上游调用：「HearingFinalRoundRecoveryService.RecoveryCursor.start()」的上游调用点包括 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」。
        // 下游影响：「HearingFinalRoundRecoveryService.RecoveryCursor.start()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RecoveryCursor」交给调用方。
        // 系统意义：「HearingFinalRoundRecoveryService.RecoveryCursor.start()」负责主链路中的“恢复Cursor”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static RecoveryCursor start() {
            return new RecoveryCursor(null, "");
        }

        // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalRoundRecoveryService.RecoveryCursor.after(HearingRoundEntity)」。
        // 具体功能：「HearingFinalRoundRecoveryService.RecoveryCursor.after(HearingRoundEntity)」：构建之后；实际协作者为 「round.getClosedAt」、「round.getId」；不满足前置条件时抛出 「IllegalStateException」，最终返回「RecoveryCursor」。
        // 上游调用：「HearingFinalRoundRecoveryService.RecoveryCursor.after(HearingRoundEntity)」的上游调用点包括 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」。
        // 下游影响：「HearingFinalRoundRecoveryService.RecoveryCursor.after(HearingRoundEntity)」向下依次触达 「round.getClosedAt」、「round.getId」；计算结果以「RecoveryCursor」交给调用方。
        // 系统意义：「HearingFinalRoundRecoveryService.RecoveryCursor.after(HearingRoundEntity)」负责主链路中的“之后”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static RecoveryCursor after(
                com.example.dispute.hearing.infrastructure.persistence.entity
                                .HearingRoundEntity
                        round) {
            if (round.getClosedAt() == null) {
                throw new IllegalStateException(
                        "sealed final round must have closedAt for keyset recovery");
            }
            return new RecoveryCursor(round.getClosedAt(), round.getId());
        }

        // 所属模块：【共享小法庭 / 应用编排层】「HearingFinalRoundRecoveryService.RecoveryCursor.isStart()」。
        // 具体功能：「HearingFinalRoundRecoveryService.RecoveryCursor.isStart()」：判断是否Start：先更新内部状态 「closedAt」，最终返回「boolean」。
        // 上游调用：「HearingFinalRoundRecoveryService.RecoveryCursor.isStart()」只由「RecoveryCursor」内部流程使用，负责封装“Start”这一步校验、映射或状态转换。
        // 下游影响：「HearingFinalRoundRecoveryService.RecoveryCursor.isStart()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
        // 系统意义：「HearingFinalRoundRecoveryService.RecoveryCursor.isStart()」负责主链路中的“Start”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private boolean isStart() {
            return closedAt == null;
        }
    }
}
