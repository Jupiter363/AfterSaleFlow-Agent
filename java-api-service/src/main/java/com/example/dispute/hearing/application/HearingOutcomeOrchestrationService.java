/*
 * 所属模块：共享小法庭。
 * 文件职责：编排庭审结束后的草案、评议和终审任务收敛规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「orchestrate」、「recoverCompletedHearingsWithoutReview」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingOutcomeOrchestrationService」。
// 类型职责：编排庭审结束后的草案、评议和终审任务收敛规则、权限校验与事实读写；本类型显式提供 「HearingOutcomeOrchestrationService」、「orchestrate」、「recoverCompletedHearingsWithoutReview」、「recoverSingleCompletedHearing」、「orchestrate」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.complete」、「HearingOutcomeRecoveryScheduler.recover」、「HearingRoundService.completeHearing」、「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingOutcomeOrchestrationService {

    private static final String ADJOURNMENT_MESSAGE =
            "庭审草案已记录，本庭休庭。案件已进入人工审核流程，平台审核员将在一个工作日内完成处理。审核完成后，您可以查看最终结果。";
    private static final String SHARED_COURT_AUDIENCE_JSON =
            "[\"USER\",\"MERCHANT\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]";

    private static final Logger log =
            LoggerFactory.getLogger(HearingOutcomeOrchestrationService.class);

    private final HearingStateRepository hearingRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final RemedyPlanRepository remedyRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomMessageRepository messageRepository;
    private final RemedyApplicationService remedyService;
    private final ReviewApplicationService reviewService;
    private final TransactionTemplate recoveryTransaction;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeOrchestrationService.HearingOutcomeOrchestrationService(HearingStateRepository,AdjudicationDraftRepository,RemedyPlanRepository,ReviewTaskRepository,RemedyApplicationService,ReviewApplicationService,PlatformTransactionManager)」。
    // 具体功能：「HearingOutcomeOrchestrationService.HearingOutcomeOrchestrationService(HearingStateRepository,AdjudicationDraftRepository,RemedyPlanRepository,ReviewTaskRepository,RemedyApplicationService,ReviewApplicationService,PlatformTransactionManager)」：通过构造器接收 「hearingRepository」(HearingStateRepository)、「draftRepository」(AdjudicationDraftRepository)、「remedyRepository」(RemedyPlanRepository)、「reviewTaskRepository」(ReviewTaskRepository)、「remedyService」(RemedyApplicationService)、「reviewService」(ReviewApplicationService)、「transactionManager」(PlatformTransactionManager) 并保存为「HearingOutcomeOrchestrationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingOutcomeOrchestrationService.HearingOutcomeOrchestrationService(HearingStateRepository,AdjudicationDraftRepository,RemedyPlanRepository,ReviewTaskRepository,RemedyApplicationService,ReviewApplicationService,PlatformTransactionManager)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「HearingOutcomeOrchestrationService.HearingOutcomeOrchestrationService(HearingStateRepository,AdjudicationDraftRepository,RemedyPlanRepository,ReviewTaskRepository,RemedyApplicationService,ReviewApplicationService,PlatformTransactionManager)」向下依次触达 「this.recoveryTransaction.setPropagationBehavior」。
    // 系统意义：「HearingOutcomeOrchestrationService.HearingOutcomeOrchestrationService(HearingStateRepository,AdjudicationDraftRepository,RemedyPlanRepository,ReviewTaskRepository,RemedyApplicationService,ReviewApplicationService,PlatformTransactionManager)」负责主链路中的“庭审结果Orchestration服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingOutcomeOrchestrationService(
            HearingStateRepository hearingRepository,
            AdjudicationDraftRepository draftRepository,
            RemedyPlanRepository remedyRepository,
            ReviewTaskRepository reviewTaskRepository,
            CaseRoomRepository roomRepository,
            RoomMessageRepository messageRepository,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            PlatformTransactionManager transactionManager) {
        this.hearingRepository = hearingRepository;
        this.draftRepository = draftRepository;
        this.remedyRepository = remedyRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.remedyService = remedyService;
        this.reviewService = reviewService;
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeOrchestrationService.orchestrate(String,String)」。
    // 具体功能：「HearingOutcomeOrchestrationService.orchestrate(String,String)」：仅对 COMPLETED 且已有最终草案的 HearingState 工作：幂等生成或复用 RemedyPlan，再生成或复用 ReviewTask，最后把 HearingState 推进到 REVIEW_GATE_READY，最终返回「HearingOutcomeOrchestrationResult」。
    // 上游调用：「HearingOutcomeOrchestrationService.orchestrate(String,String)」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」、「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing」、「HearingRoundService.completeHearing」、「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「HearingOutcomeOrchestrationService.orchestrate(String,String)」向下依次触达 「hearingRepository.findByCaseId」、「orchestrate」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingOutcomeOrchestrationService.orchestrate(String,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public HearingOutcomeOrchestrationResult orchestrate(
            String caseId, String actorId) {
        HearingStateEntity hearing =
                hearingRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "hearing state not found",
                                                Map.of("case_id", caseId)));
        return orchestrate(hearing);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview(int)」。
    // 具体功能：「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview(int)」：分页扫描已完成但缺少审核任务的庭审，每个案件用独立 REQUIRES_NEW 事务恢复，避免一个脏案件回滚整批并支持调度器重复执行，最终返回「int」。
    // 上游调用：「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview(int)」的上游调用点包括 「HearingOutcomeRecoveryScheduler.recover」、「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」。
    // 下游影响：「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview(int)」向下依次触达 「hearingRepository.findAllByHearingStatusOrderByCompletedAtAsc」、「recoveryTransaction.execute」、「log.warn」、「recoverSingleCompletedHearing」；计算结果以「int」交给调用方。
    // 系统意义：「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview(int)」负责主链路中的“完成Hearings缺少审核”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public int recoverCompletedHearingsWithoutReview(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int recovered = 0;
        for (String caseId :
                hearingRepository.findAllByHearingStatusOrderByCompletedAtAsc(
                                HearingStatus.COMPLETED)
                        .stream()
                        .map(HearingStateEntity::getCaseId)
                        .toList()) {
            if (recovered >= limit) {
                break;
            }
            try {
                Boolean created =
                        recoveryTransaction.execute(
                                status -> recoverSingleCompletedHearing(caseId));
                if (Boolean.TRUE.equals(created)) {
                    recovered++;
                }
            } catch (BusinessException ex) {
                log.warn(
                        "Skipping completed hearing recovery for case {} because {}",
                        caseId,
                        ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn(
                        "Skipping completed hearing recovery for case {} because orchestration failed",
                        caseId,
                        ex);
            }
        }
        return recovered;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing(String)」。
    // 具体功能：「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing(String)」：恢复Single完成庭审；实际协作者为 「hearingRepository.findByCaseId」、「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「hearing.getHearingStatus」，最终返回「boolean」。
    // 上游调用：「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing(String)」的上游调用点包括 「HearingOutcomeOrchestrationService.recoverCompletedHearingsWithoutReview」。
    // 下游影响：「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing(String)」向下依次触达 「hearingRepository.findByCaseId」、「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「hearing.getHearingStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing(String)」负责主链路中的“Single完成庭审”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private boolean recoverSingleCompletedHearing(String caseId) {
        HearingStateEntity hearing =
                hearingRepository
                        .findByCaseId(caseId)
                        .orElse(null);
        if (hearing == null || hearing.getHearingStatus() != HearingStatus.COMPLETED) {
            return false;
        }
        var draft = draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId);
        if (draft.isEmpty()) {
            log.warn(
                    "Skipping completed hearing recovery for case {} because adjudication draft is missing",
                    caseId);
            return false;
        }
        var plan = remedyRepository.findFirstByCaseIdOrderByPlanVersionDesc(caseId);
        var task = reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId);
        boolean terminalMessagesMissing =
                plan.isPresent()
                        && task.isPresent()
                        && (messageRepository
                                        .findByCaseIdAndIdempotencyKey(
                                                caseId,
                                                finalDraftIdempotencyKey(
                                                        caseId, draft.get().getId()))
                                        .isEmpty()
                                || messageRepository
                                        .findByCaseIdAndIdempotencyKey(
                                                caseId,
                                                adjournmentIdempotencyKey(
                                                        caseId, task.get().getId()))
                                        .isEmpty());
        HearingOutcomeOrchestrationResult result = orchestrate(hearing);
        return result.createdRemedy() || result.createdReviewTask() || terminalMessagesMissing;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeOrchestrationService.orchestrate(HearingStateEntity)」。
    // 具体功能：「HearingOutcomeOrchestrationService.orchestrate(HearingStateEntity)」：仅对 COMPLETED 且已有最终草案的 HearingState 工作：幂等生成或复用 RemedyPlan，再生成或复用 ReviewTask，最后把 HearingState 推进到 REVIEW_GATE_READY，最终返回「HearingOutcomeOrchestrationResult」。
    // 上游调用：「HearingOutcomeOrchestrationService.orchestrate(HearingStateEntity)」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」、「HearingOutcomeOrchestrationService.recoverSingleCompletedHearing」、「HearingRoundService.completeHearing」、「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「HearingOutcomeOrchestrationService.orchestrate(HearingStateEntity)」向下依次触达 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「remedyRepository.findFirstByCaseIdOrderByPlanVersionDesc」、「remedyService.generateForWorkflow」、「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」；计算结果以「HearingOutcomeOrchestrationResult」交给调用方。
    // 系统意义：「HearingOutcomeOrchestrationService.orchestrate(HearingStateEntity)」负责主链路中的“庭审结果Orchestration”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private HearingOutcomeOrchestrationResult orchestrate(
            HearingStateEntity hearing) {
        if (hearing.getHearingStatus() != HearingStatus.COMPLETED) {
            return new HearingOutcomeOrchestrationResult(
                    hearing.getCaseId(), null, null, false, false, "SKIPPED_NOT_COMPLETED");
        }
        AdjudicationDraftEntity finalDraft =
                draftRepository
                .findFirstByCaseIdOrderByDraftVersionDesc(hearing.getCaseId())
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.CASE_STATUS_INVALID,
                                        "adjudication draft is required before review gate",
                                        Map.of("case_id", hearing.getCaseId())));

        var existingPlan =
                remedyRepository.findFirstByCaseIdOrderByPlanVersionDesc(
                        hearing.getCaseId());
        boolean createdRemedy = existingPlan.isEmpty();
        String remedyPlanId =
                existingPlan
                        .map(plan -> plan.getId())
                        .orElseGet(
                                () ->
                                        remedyService.generateForWorkflow(
                                                hearing.getCaseId(),
                                                hearing.getWorkflowId()));

        var existingTask =
                reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                        hearing.getCaseId());
        boolean createdReviewTask = existingTask.isEmpty();
        String reviewTaskId =
                existingTask
                        .map(task -> task.getId())
                        .orElseGet(
                                () ->
                                        reviewService.createForWorkflow(
                                                hearing.getCaseId(), remedyPlanId));

        remedyRepository
                .findById(remedyPlanId)
                .filter(plan -> hearing.getCaseId().equals(plan.getCaseId()))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "review gate requires a persisted remedy plan"));
        reviewTaskRepository
                .findById(reviewTaskId)
                .filter(task -> hearing.getCaseId().equals(task.getCaseId()))
                .filter(task -> remedyPlanId.equals(task.getPlanId()))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "review gate requires a persisted review task"));
        appendFinalDraftMessage(hearing, finalDraft);
        appendAdjournmentMessage(hearing, reviewTaskId);

        return new HearingOutcomeOrchestrationResult(
                hearing.getCaseId(),
                remedyPlanId,
                reviewTaskId,
                createdRemedy,
                createdReviewTask,
                "REVIEW_GATE_READY");
    }

    private void appendFinalDraftMessage(
            HearingStateEntity hearing,
            AdjudicationDraftEntity draft) {
        String idempotencyKey =
                finalDraftIdempotencyKey(hearing.getCaseId(), draft.getId());
        if (messageRepository
                .findByCaseIdAndIdempotencyKey(hearing.getCaseId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        var room =
                roomRepository
                        .findByCaseIdAndRoomTypeForUpdate(
                                hearing.getCaseId(), RoomType.HEARING)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing room is required before final draft handoff"));
        if (messageRepository
                .findByCaseIdAndIdempotencyKey(hearing.getCaseId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        String draftMessage =
                "裁决草案 V2（非最终草案）\n"
                        + "拟处理方向："
                        + draft.getRecommendedDecision()
                        + "\n"
                        + "理由摘要："
                        + draft.getDraftText()
                        + "\n本草案已提交平台审核员确认，尚未生效。";
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity message =
                RoomMessageEntity.create(
                        "MESSAGE_" + compactUuid(),
                        hearing.getCaseId(),
                        room.getId(),
                        sequence,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        SHARED_COURT_AUDIENCE_JSON,
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        draftMessage,
                        "[]",
                        idempotencyKey,
                        hearing.getRoundNo(),
                        Instant.now(),
                        "TRACE_HEARING_FINAL_DRAFT_" + hearing.getCaseId());
        if (draft.getCreatedByAgentRunId() != null
                && !draft.getCreatedByAgentRunId().isBlank()) {
            message.attachAgentRun(draft.getCreatedByAgentRunId());
        }
        messageRepository.save(message);
    }

    private void appendAdjournmentMessage(
            HearingStateEntity hearing,
            String reviewTaskId) {
        String idempotencyKey =
                adjournmentIdempotencyKey(hearing.getCaseId(), reviewTaskId);
        if (messageRepository
                .findByCaseIdAndIdempotencyKey(hearing.getCaseId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        var room =
                roomRepository
                        .findByCaseIdAndRoomTypeForUpdate(
                                hearing.getCaseId(), RoomType.HEARING)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing room is required before adjournment"));
        if (messageRepository
                .findByCaseIdAndIdempotencyKey(hearing.getCaseId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        messageRepository.save(
                RoomMessageEntity.create(
                        "MESSAGE_" + compactUuid(),
                        hearing.getCaseId(),
                        room.getId(),
                        sequence,
                        MessageSenderType.SYSTEM,
                        "SYSTEM",
                        "hearing-system",
                        SHARED_COURT_AUDIENCE_JSON,
                        "[]",
                        MessageType.SYSTEM_EVENT,
                        ADJOURNMENT_MESSAGE,
                        "[]",
                        idempotencyKey,
                        hearing.getRoundNo(),
                        Instant.now(),
                        "TRACE_HEARING_ADJOURNMENT_" + hearing.getCaseId()));
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String adjournmentIdempotencyKey(
            String caseId, String reviewTaskId) {
        return "hearing-adjourned:" + caseId + ":" + reviewTaskId;
    }

    private static String finalDraftIdempotencyKey(
            String caseId, String draftId) {
        return "hearing-final-draft-v2:" + caseId + ":" + draftId;
    }
}
