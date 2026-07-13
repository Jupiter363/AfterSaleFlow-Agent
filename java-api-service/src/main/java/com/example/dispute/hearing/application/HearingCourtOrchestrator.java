/*
 * 所属模块：共享小法庭。
 * 文件职责：承载庭审轮次开放、Agent 回合和终局交接在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「afterRoundOpenedAfterCommit」、「afterRoundClosedAfterCommit」、「afterRoundOpened」、「afterRoundClosed」、「supports」、「finalizeResult」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunFinalizer;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingCourtOrchestrator」。
// 类型职责：承载庭审轮次开放、Agent 回合和终局交接在当前业务模块中的规则与协作边界；本类型显式提供 「HearingCourtOrchestrator」、「HearingCourtOrchestrator」、「afterRoundOpenedAfterCommit」、「afterRoundClosedAfterCommit」、「afterRoundClosedAfterCommit」、「afterRoundOpened」。
// 协作关系：主要由 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingRoundService.dispatchRoundClosedAfterCommit」、「HearingCourtOrchestratorTest.afterCommitRoundTurnFailuresDoNotPropagateToTheBusinessRequest」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingCourtOrchestrator implements AgentRunFinalizer {

    public static final String JUDGE_SENDER_ROLE = "JUDGE";
    public static final String JUDGE_SENDER_ID = "presiding-judge";

    private static final Set<String> REQUIRED_JURY_REVIEW_DIMENSIONS =
            Set.of(
                    "FACT_COMPLETENESS",
                    "EVIDENCE_CONSISTENCY",
                    "RULE_APPLICABILITY",
                    "PROCEDURAL_FAIRNESS",
                    "REMEDY_FEASIBILITY",
                    "RISK_AND_OMISSIONS");

    private static final Logger log = LoggerFactory.getLogger(HearingCourtOrchestrator.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final HearingCourtAgentClient agentClient;
    private final AgentA2AMessageService a2aMessageService;
    private final ActiveCourtroomContextAssembler courtroomContextAssembler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PostCommitSideEffectExecutor postCommit;
    private final TransactionTemplate courtTransaction;
    private AgentRunCoordinator agentRunCoordinator;
    private HearingWorkflowCoordinator workflowCoordinator;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate)」。
    // 具体功能：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「roundRepository」(HearingRoundRepository)、「submissionRepository」(HearingRoundPartySubmissionRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「agentClient」(HearingCourtAgentClient)、「a2aMessageService」(AgentA2AMessageService)、「courtroomContextAssembler」(ActiveCourtroomContextAssembler)、「objectMapper」(ObjectMapper)、「clock」(Clock)、「postCommit」(PostCommitSideEffectExecutor)、「courtTransaction」(TransactionTemplate) 并保存为「HearingCourtOrchestrator」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate)」的上游创建点包括 「HearingCourtOrchestratorTest.setUp」、「HearingCourtOrchestratorTest.invokesTheRemoteCourtAgentOutsideAnyActiveCourtTransaction」、「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists」。
    // 下游影响：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate)」负责主链路中的“庭审法庭编排器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingCourtOrchestrator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingCourtAgentClient agentClient,
            AgentA2AMessageService a2aMessageService,
            ActiveCourtroomContextAssembler courtroomContextAssembler,
            ObjectMapper objectMapper,
            Clock clock,
            PostCommitSideEffectExecutor postCommit,
            TransactionTemplate courtTransaction) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.agentClient = agentClient;
        this.a2aMessageService = a2aMessageService;
        this.courtroomContextAssembler = courtroomContextAssembler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.postCommit = postCommit;
        this.courtTransaction = courtTransaction;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate,AgentRunCoordinator,HearingWorkflowCoordinator)」。
    // 具体功能：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate,AgentRunCoordinator,HearingWorkflowCoordinator)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「roundRepository」(HearingRoundRepository)、「submissionRepository」(HearingRoundPartySubmissionRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「agentClient」(HearingCourtAgentClient)、「a2aMessageService」(AgentA2AMessageService)、「courtroomContextAssembler」(ActiveCourtroomContextAssembler)、「objectMapper」(ObjectMapper)、「clock」(Clock)、「postCommit」(PostCommitSideEffectExecutor)、「courtTransaction」(TransactionTemplate)、「agentRunCoordinator」(AgentRunCoordinator)、「workflowCoordinator」(HearingWorkflowCoordinator) 并保存为「HearingCourtOrchestrator」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate,AgentRunCoordinator,HearingWorkflowCoordinator)」的上游创建点包括 「HearingCourtOrchestratorTest.setUp」、「HearingCourtOrchestratorTest.invokesTheRemoteCourtAgentOutsideAnyActiveCourtTransaction」、「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists」。
    // 下游影响：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate,AgentRunCoordinator,HearingWorkflowCoordinator)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCourtOrchestrator.HearingCourtOrchestrator(FulfillmentCaseRepository,CaseRoomRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,RoomMessageRepository,CaseEventService,HearingCourtAgentClient,AgentA2AMessageService,ActiveCourtroomContextAssembler,ObjectMapper,Clock,PostCommitSideEffectExecutor,TransactionTemplate,AgentRunCoordinator,HearingWorkflowCoordinator)」负责主链路中的“庭审法庭编排器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public HearingCourtOrchestrator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingCourtAgentClient agentClient,
            AgentA2AMessageService a2aMessageService,
            ActiveCourtroomContextAssembler courtroomContextAssembler,
            ObjectMapper objectMapper,
            Clock clock,
            PostCommitSideEffectExecutor postCommit,
            TransactionTemplate courtTransaction,
            AgentRunCoordinator agentRunCoordinator,
            HearingWorkflowCoordinator workflowCoordinator) {
        this(
                caseRepository,
                roomRepository,
                roundRepository,
                submissionRepository,
                messageRepository,
                eventService,
                agentClient,
                a2aMessageService,
                courtroomContextAssembler,
                objectMapper,
                clock,
                postCommit,
                courtTransaction);
        this.agentRunCoordinator = agentRunCoordinator;
        this.workflowCoordinator = workflowCoordinator;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.afterRoundOpenedAfterCommit(String,int,String)」。
    // 具体功能：「HearingCourtOrchestrator.afterRoundOpenedAfterCommit(String,int,String)」：在轮次实体与开庭事件提交成功后再生成法官开场，避免 Agent 读取不到刚开放的轮次；稳定键保证重试不重复发言，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.afterRoundOpenedAfterCommit(String,int,String)」由使用「HearingCourtOrchestrator」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingCourtOrchestrator.afterRoundOpenedAfterCommit(String,int,String)」向下依次触达 「postCommit.execute」、「afterRoundOpened」。
    // 系统意义：「HearingCourtOrchestrator.afterRoundOpenedAfterCommit(String,int,String)」负责主链路中的“之后轮次Opened之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void afterRoundOpenedAfterCommit(String caseId, int roundNo, String traceId) {
        postCommit.execute(
                "hearing-court-round-opened",
                Map.of("case_id", caseId, "round_no", roundNo),
                () -> afterRoundOpened(caseId, roundNo, traceId));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String)」。
    // 具体功能：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String)」：在轮次封存事务提交后触发法官总结；已有完整法官回合时直接执行 completion，否则启动/恢复 HEARING_ROUND AgentRun，最终轮还要求正式评审团报告齐备，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosedAfterCommit」、「HearingRoundService.dispatchRoundClosedAfterCommit」、「HearingCourtOrchestratorTest.afterCommitRoundTurnFailuresDoNotPropagateToTheBusinessRequest」、「HearingCourtOrchestratorTest.afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion」。
    // 下游影响：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String)」向下依次触达 「afterRoundClosedAfterCommit」。
    // 系统意义：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String)」负责主链路中的“之后轮次Closed之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void afterRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        afterRoundClosedAfterCommit(caseId, roundNo, finalRound, traceId, () -> {});
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String,Runnable)」。
    // 具体功能：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String,Runnable)」：在轮次封存事务提交后触发法官总结；已有完整法官回合时直接执行 completion，否则启动/恢复 HEARING_ROUND AgentRun，最终轮还要求正式评审团报告齐备，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String,Runnable)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosedAfterCommit」、「HearingRoundService.dispatchRoundClosedAfterCommit」、「HearingCourtOrchestratorTest.afterCommitRoundTurnFailuresDoNotPropagateToTheBusinessRequest」、「HearingCourtOrchestratorTest.afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion」。
    // 下游影响：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String,Runnable)」向下依次触达 「postCommit.execute」、「completion.run」、「afterRoundClosed」、「isJudgeTurnComplete」。
    // 系统意义：「HearingCourtOrchestrator.afterRoundClosedAfterCommit(String,int,boolean,String,Runnable)」负责主链路中的“之后轮次Closed之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void afterRoundClosedAfterCommit(
            String caseId,
            int roundNo,
            boolean finalRound,
            String traceId,
            Runnable completion) {
        postCommit.execute(
                "hearing-court-round-closed",
                Map.of("case_id", caseId, "round_no", roundNo, "final_round", finalRound),
                () -> {
                    afterRoundClosed(caseId, roundNo, finalRound, traceId);
                    if (agentRunCoordinator == null
                            || isJudgeTurnComplete(caseId, roundNo, finalRound)) {
                        completion.run();
                    }
                });
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.afterRoundOpened(String,int,String)」。
    // 具体功能：「HearingCourtOrchestrator.afterRoundOpened(String,int,String)」：执行之后轮次Opened；实际协作者为 「processJudgeTurn」、「judgeRoundOpeningKey」；处理的关键状态/协议值包括 「judge-round-opening-ready:」、「:」，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.afterRoundOpened(String,int,String)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundOpenedAfterCommit」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」。
    // 下游影响：「HearingCourtOrchestrator.afterRoundOpened(String,int,String)」向下依次触达 「processJudgeTurn」、「judgeRoundOpeningKey」。
    // 系统意义：「HearingCourtOrchestrator.afterRoundOpened(String,int,String)」负责主链路中的“之后轮次Opened”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void afterRoundOpened(String caseId, int roundNo, String traceId) {
        processJudgeTurn(
                caseId,
                roundNo,
                false,
                judgeRoundOpeningKey(caseId, roundNo),
                "judge-round-opening-ready:" + caseId + ":" + roundNo,
                traceId);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.afterRoundClosed(String,int,boolean,String)」。
    // 具体功能：「HearingCourtOrchestrator.afterRoundClosed(String,int,boolean,String)」：更新之后轮次Closed：先更新内部状态 「agentRunCoordinator」；实际协作者为 「processJudgeTurn」、「judgeRoundTurnKey」、「hasCompleteFormalJuryReport」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「judge-round-turn-ready:」、「:」，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.afterRoundClosed(String,int,boolean,String)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosedAfterCommit」、「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingCourtOrchestratorTest.afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent」、「HearingCourtOrchestratorTest.finalRoundRetryReusesTheSurvivingA2APayloadWhenRepairingTheRoomCard」。
    // 下游影响：「HearingCourtOrchestrator.afterRoundClosed(String,int,boolean,String)」向下依次触达 「processJudgeTurn」、「judgeRoundTurnKey」、「hasCompleteFormalJuryReport」。
    // 系统意义：「HearingCourtOrchestrator.afterRoundClosed(String,int,boolean,String)」负责主链路中的“之后轮次Closed”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void afterRoundClosed(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        processJudgeTurn(
                caseId,
                roundNo,
                finalRound,
                judgeRoundTurnKey(caseId, roundNo),
                "judge-round-turn-ready:" + caseId + ":" + roundNo,
                traceId);
        if (agentRunCoordinator == null
                && finalRound
                && !hasCompleteFormalJuryReport(caseId, roundNo)) {
            throw new IllegalStateException(
                    "final hearing convergence requires both formal jury A2A and room report");
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.processJudgeTurn(String,int,boolean,String,String,String)」。
    // 具体功能：「HearingCourtOrchestrator.processJudgeTurn(String,int,boolean,String,String,String)」：先在短事务中锁定案件、轮次和提交并判断 completed/repair/generate 三种准备结果；需要模型时在事务外调用 Agent，随后用第二个事务幂等保存，避免长事务占用数据库锁，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.processJudgeTurn(String,int,boolean,String,String,String)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundOpened」、「HearingCourtOrchestrator.afterRoundClosed」。
    // 下游影响：「HearingCourtOrchestrator.processJudgeTurn(String,int,boolean,String,String,String)」向下依次触达 「courtTransaction.execute」、「preparation.complete」、「preparation.command」、「courtTransaction.executeWithoutResult」。
    // 系统意义：「HearingCourtOrchestrator.processJudgeTurn(String,int,boolean,String,String,String)」负责主链路中的“法官轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void processJudgeTurn(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey,
            String traceId) {
        // 第一个短事务只负责锁行、检查幂等键并冻结 Agent 输入。
        // 外部模型调用绝不能占着案件/轮次数据库锁等待网络响应。
        TurnPreparation preparation =
                courtTransaction.execute(
                        ignored ->
                                prepareJudgeTurn(
                                        caseId,
                                        roundNo,
                                        finalRound,
                                        idempotencyKey,
                                        lifecycleEventKey));
        if (preparation == null || preparation.complete()) {
            return;
        }
        if (agentRunCoordinator != null && preparation.command() != null) {
            // 新链路把模型调用交给可恢复 AgentRun；当前方法返回后由 Finalizer 完成第二个事务。
            startStreamingJudgeTurn(preparation, traceId);
            return;
        }
        // 兼容同步链路同样在事务外生成，再开启独立短事务持久化结果。
        HearingCourtAgentResult generated =
                preparation.command() == null
                        ? null
                        : safeGenerate(preparation.command(), traceId);
        courtTransaction.executeWithoutResult(
                ignored -> persistJudgeTurn(preparation, generated, traceId));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.startStreamingJudgeTurn(TurnPreparation,String)」。
    // 具体功能：「HearingCourtOrchestrator.startStreamingJudgeTurn(TurnPreparation,String)」：把冻结的 TurnPreparation 命令和共享法庭受众封装为 HEARING_ROUND AgentRun，以 case+round+阶段作为幂等键启动；不存在法庭房间时拒绝运行，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.startStreamingJudgeTurn(TurnPreparation,String)」的上游调用点包括 「HearingCourtOrchestrator.processJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.startStreamingJudgeTurn(TurnPreparation,String)」向下依次触达 「agentRunCoordinator.start」、「roomRepository.findByCaseIdAndRoomType」、「preparation.command」、「preparation.caseId」。
    // 系统意义：「HearingCourtOrchestrator.startStreamingJudgeTurn(TurnPreparation,String)」负责主链路中的“Streaming法官轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void startStreamingJudgeTurn(TurnPreparation preparation, String traceId) {
        HearingCourtAgentCommand command = preparation.command();
        // 请求、受众和幂等键会随 AgentRun 一起持久化；Worker 重启后无需重新读取可变庭审上下文。
        agentRunCoordinator.start(
                new AgentRunStartCommand(
                        preparation.caseId(),
                        roomRepository
                                .findByCaseIdAndRoomType(
                                        preparation.caseId(), RoomType.HEARING)
                                .orElseThrow(
                                        () -> new IllegalArgumentException(
                                                "hearing room not found"))
                                .getId(),
                        "HEARING_ROUND",
                        judgeRequest(command),
                        List.of(
                                ActorRole.USER.name(),
                                ActorRole.MERCHANT.name(),
                                ActorRole.CUSTOMER_SERVICE.name(),
                                ActorRole.PLATFORM_REVIEWER.name(),
                                ActorRole.ADMIN.name(),
                                ActorRole.SYSTEM.name()),
                        List.of(),
                        preparation.idempotencyKey(),
                        traceId,
                        "REQ_HEARING_ROUND_" + preparation.caseId() + "_" + preparation.roundNo(),
                        JUDGE_SENDER_ID));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.supports(String)」。
    // 具体功能：「HearingCourtOrchestrator.supports(String)」：判断是否支持；处理的关键状态/协议值包括 「HEARING_ROUND」，最终返回「boolean」。
    // 上游调用：「HearingCourtOrchestrator.supports(String)」由使用「HearingCourtOrchestrator」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingCourtOrchestrator.supports(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.supports(String)」负责主链路中的“”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    @Override
    public boolean supports(String operation) {
        return "HEARING_ROUND".equals(operation);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「HearingCourtOrchestrator.finalizeResult(AgentRunFinalizationContext,JsonNode)」：作为 HEARING_ROUND Finalizer 重建持久化 TurnPreparation，校验模型轮次、事件类型和终局标志，原子保存法官消息/A2A 记录后才向 Hearing Workflow 发送 roundCompleted，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.finalizeResult(AgentRunFinalizationContext,JsonNode)」由使用「HearingCourtOrchestrator」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingCourtOrchestrator.finalizeResult(AgentRunFinalizationContext,JsonNode)」向下依次触达 「workflowCoordinator.roundCompletedAfterCommit」、「TurnPreparation.generate」、「finalization.request」、「finalization.caseId」。
    // 系统意义：「HearingCourtOrchestrator.finalizeResult(AgentRunFinalizationContext,JsonNode)」负责主链路中的“finalize结果”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    @Override
    public void finalizeResult(AgentRunFinalizationContext finalization, JsonNode rawResult) {
        JsonNode request = finalization.request();
        int roundNo = request.path("round_no").asInt(0);
        if (roundNo < 1) {
            throw new IllegalStateException("hearing stream is missing round_no");
        }
        boolean finalRound = request.path("final_round").asBoolean(false);
        boolean opening =
                "OPEN".equals(request.path("round_status").asText())
                        && request.path("party_submissions").isArray()
                        && request.path("party_submissions").isEmpty();
        String lifecycleEventKey =
                (opening ? "judge-round-opening-ready:" : "judge-round-turn-ready:")
                        + finalization.caseId()
                        + ":"
                        + roundNo;
        // A run retry has an attempt-scoped stream key, while the persisted court message has
        // one stable domain identity per case and round. Never leak the retry key into the room
        // message or the same finalization cannot bind its jury report to the judge proposal.
        String judgeMessageKey =
                opening
                        ? judgeRoundOpeningKey(finalization.caseId(), roundNo)
                        : judgeRoundTurnKey(finalization.caseId(), roundNo);
        TurnPreparation preparation =
                TurnPreparation.generate(
                        finalization.caseId(),
                        roundNo,
                        finalRound,
                        judgeMessageKey,
                        lifecycleEventKey,
                        null);
        HearingCourtAgentResult result =
                objectMapper.convertValue(rawResult, HearingCourtAgentResult.class);
        validateStreamingJudgeResult(result, roundNo, finalRound, opening);
        persistJudgeTurn(
                preparation, result, finalization.traceId(), finalization.runId());
        if (finalRound && !hasCompleteFormalJuryReport(finalization.caseId(), roundNo)) {
            throw new IllegalStateException(
                    "final hearing convergence requires a formal jury report");
        }
        if (!opening && workflowCoordinator != null) {
            workflowCoordinator.roundCompletedAfterCommit(
                    finalization.caseId(), roundNo, false);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.validateStreamingJudgeResult(HearingCourtAgentResult,int,boolean,boolean)」。
    // 具体功能：「HearingCourtOrchestrator.validateStreamingJudgeResult(HearingCourtAgentResult,int,boolean,boolean)」：要求模型 roundNo 与请求一致、speakerRole 为 PRESIDING_JUDGE，并约束 opening/普通回合/最终回合允许的 courtEventType 和 finalDraftRequired，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.validateStreamingJudgeResult(HearingCourtAgentResult,int,boolean,boolean)」的上游调用点包括 「HearingCourtOrchestrator.finalizeResult」。
    // 下游影响：「HearingCourtOrchestrator.validateStreamingJudgeResult(HearingCourtAgentResult,int,boolean,boolean)」向下依次触达 「result.roundNo」、「result.speakerRole」、「result.finalDraftRequired」、「result.courtEventType」。
    // 系统意义：「HearingCourtOrchestrator.validateStreamingJudgeResult(HearingCourtAgentResult,int,boolean,boolean)」在“Streaming法官结果”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void validateStreamingJudgeResult(
            HearingCourtAgentResult result,
            int expectedRoundNo,
            boolean finalRound,
            boolean opening) {
        if (result.roundNo() != expectedRoundNo) {
            throw new IllegalStateException(
                    "hearing stream result does not match the requested round");
        }
        if (!JUDGE_SENDER_ROLE.equals(result.speakerRole())) {
            throw new IllegalStateException("hearing stream result has an invalid speaker");
        }
        if (opening) {
            if (result.finalDraftRequired()
                    || !"JUDGE_OPENING_READY".equals(result.courtEventType())) {
                throw new IllegalStateException(
                        "hearing opening stream attempted to advance the court state");
            }
            return;
        }
        if (finalRound
                != (result.finalDraftRequired()
                        && "FINAL_DRAFT_REQUIRED".equals(result.courtEventType()))) {
            throw new IllegalStateException(
                    "hearing stream result does not match the final-round state");
        }
        if (finalRound) {
            if (result.finalProposedResolution() == null
                    || result.finalProposedResolution().isBlank()) {
                throw new IllegalStateException(
                        "final hearing turn requires a non-final proposed resolution");
            }
            if (result.juryReviewReport().isEmpty()) {
                throw new IllegalStateException(
                        "final hearing turn requires a model-generated jury review report");
            }
        } else if (result.finalProposedResolution() != null) {
            throw new IllegalStateException(
                    "non-final hearing turn cannot publish a final proposed resolution");
        }
        if (!finalRound
                && !"JUDGE_NEXT_QUESTIONS_READY".equals(result.courtEventType())) {
            throw new IllegalStateException(
                    "non-final hearing stream attempted an invalid state transition");
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.isJudgeTurnComplete(String,int,boolean)」。
    // 具体功能：「HearingCourtOrchestrator.isJudgeTurnComplete(String,int,boolean)」：判断是否法官轮次协议完整性；实际协作者为 「messageRepository.findByCaseIdAndIdempotencyKey」、「judgeRoundTurnKey」、「hasCompleteFormalJuryReport」、「isPresent」，最终返回「boolean」。
    // 上游调用：「HearingCourtOrchestrator.isJudgeTurnComplete(String,int,boolean)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosedAfterCommit」。
    // 下游影响：「HearingCourtOrchestrator.isJudgeTurnComplete(String,int,boolean)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「judgeRoundTurnKey」、「hasCompleteFormalJuryReport」、「isPresent」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.isJudgeTurnComplete(String,int,boolean)」负责主链路中的“法官轮次协议完整性”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private boolean isJudgeTurnComplete(
            String caseId, int roundNo, boolean finalRound) {
        boolean judgeMessageReady =
                messageRepository
                        .findByCaseIdAndIdempotencyKey(
                                caseId, judgeRoundTurnKey(caseId, roundNo))
                        .isPresent();
        return judgeMessageReady
                && (!finalRound || hasCompleteFormalJuryReport(caseId, roundNo));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.prepareJudgeTurn(String,int,boolean,String,String)」。
    // 具体功能：「HearingCourtOrchestrator.prepareJudgeTurn(String,int,boolean,String,String)」：在案件和轮次行锁内检查幂等消息、收集本轮双方提交、组装版本化 CourtroomContext；若结果已存在返回 completed，历史状态缺口返回 repair，否则返回可调用模型的 generate 命令，最终返回「TurnPreparation」。
    // 上游调用：「HearingCourtOrchestrator.prepareJudgeTurn(String,int,boolean,String,String)」的上游调用点包括 「HearingCourtOrchestrator.processJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.prepareJudgeTurn(String,int,boolean,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「caseRepository.findByIdForUpdate」、「roundRepository.findByCaseIdAndRoundNo」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」；计算结果以「TurnPreparation」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.prepareJudgeTurn(String,int,boolean,String,String)」负责主链路中的“法官轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private TurnPreparation prepareJudgeTurn(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey) {
        var existingJudgeMessage =
                messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existingJudgeMessage.isPresent()
                && (!finalRound || hasCompleteFormalJuryReport(caseId, roundNo))) {
            return TurnPreparation.completed(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey);
        }
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        HearingRoundEntity round =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, roundNo)
                        .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        caseId, roundNo);
        if (existingJudgeMessage.isPresent()) {
            HearingCourtAgentCommand recoveryCommand =
                    command(dispute, round, submissions, finalRound);
            return TurnPreparation.repair(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    recoveryCommand,
                    reviewFocusSignal(submissions));
        }
        HearingCourtAgentCommand command = command(dispute, round, submissions, finalRound);
        return TurnPreparation.generate(
                caseId,
                roundNo,
                finalRound,
                idempotencyKey,
                lifecycleEventKey,
                command);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String)」。
    // 具体功能：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String)」：重新锁定案件、法庭房间和轮次并复查幂等键，保存法官消息、生命周期事件和必要的正式评审团报告；最终草案需求以数据库当前状态为准，防止迟到 Agent 覆盖已恢复结果，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String)」的上游调用点包括 「HearingCourtOrchestrator.processJudgeTurn」、「HearingCourtOrchestrator.finalizeResult」、「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String)」向下依次触达 「persistJudgeTurn」、「compactUuid」。
    // 系统意义：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String)」负责主链路中的“法官轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void persistJudgeTurn(
            TurnPreparation preparation,
            HearingCourtAgentResult generated,
            String traceId) {
        persistJudgeTurn(
                preparation,
                generated,
                traceId,
                "HEARING_RUN_" + compactUuid());
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String,String)」。
    // 具体功能：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String,String)」：重新锁定案件、法庭房间和轮次并复查幂等键，保存法官消息、生命周期事件和必要的正式评审团报告；最终草案需求以数据库当前状态为准，防止迟到 Agent 覆盖已恢复结果，最终返回「void」。
    // 上游调用：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String,String)」的上游调用点包括 「HearingCourtOrchestrator.processJudgeTurn」、「HearingCourtOrchestrator.finalizeResult」、「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomTypeForUpdate」、「roundRepository.findByCaseIdAndRoundNoForUpdate」、「messageRepository.findByCaseIdAndIdempotencyKey」。
    // 系统意义：「HearingCourtOrchestrator.persistJudgeTurn(TurnPreparation,HearingCourtAgentResult,String,String)」负责主链路中的“法官轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void persistJudgeTurn(
            TurnPreparation preparation,
            HearingCourtAgentResult generated,
            String traceId,
            String runId) {
        // 第二个事务必须重新加锁并复查消息幂等键，因为模型运行期间可能已有恢复任务写入结果。
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(preparation.caseId())
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomTypeForUpdate(
                                preparation.caseId(), RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        roundRepository
                .findByCaseIdAndRoundNoForUpdate(
                        preparation.caseId(), preparation.roundNo())
                .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));

        Optional<RoomMessageEntity> existingJudgeMessage =
                messageRepository.findByCaseIdAndIdempotencyKey(
                        preparation.caseId(), preparation.idempotencyKey());
        RoomMessageEntity persistedJudgeProposal = existingJudgeMessage.orElse(null);
        HearingCourtAgentResult effectiveResult = generated;
        if (existingJudgeMessage.isEmpty()) {
            if (effectiveResult == null) {
                throw new IllegalStateException("judge turn disappeared during repair");
            }
            RoomMessageEntity saved =
                    appendJudgeMessage(
                            dispute,
                            room,
                            preparation.roundNo(),
                            effectiveResult,
                            preparation.idempotencyKey(),
                            traceId,
                            runId);
            persistedJudgeProposal = saved;
            eventService.recordRoomMessage(
                    dispute.getId(),
                    room.getId(),
                    saved.getId(),
                    saved.getMessageText(),
                    saved.getAudienceJson(),
                    saved.getAudienceActorIdsJson(),
                    JUDGE_SENDER_ID);
            eventService.recordLifecycleEvent(
                    dispute.getId(),
                    room.getId(),
                    effectiveResult.courtEventType(),
                    judgeLifecyclePayload(effectiveResult),
                    preparation.lifecycleEventKey(),
                    JUDGE_SENDER_ID);
        }

        boolean finalDraftRequired =
                preparation.finalRound()
                        || effectiveResult != null && effectiveResult.finalDraftRequired();
        if (!finalDraftRequired) {
            return;
        }
        appendFormalJuryReportIfNeeded(
                dispute,
                room,
                preparation.roundNo(),
                effectiveResult == null
                        ? "FINAL_DRAFT_REQUIRED"
                        : effectiveResult.courtEventType(),
                effectiveResult == null
                        ? preparation.recoveryReviewFocus()
                        : effectiveResult.reviewFocusSignal(),
                effectiveResult == null
                        ? null
                        : effectiveResult.finalProposedResolution(),
                effectiveResult == null
                        ? Map.of()
                        : effectiveResult.juryReviewReport(),
                effectiveResult == null
                        ? "hearing-round-recovery-v1"
                        : effectiveResult.promptVersion(),
                persistedJudgeProposal,
                traceId);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.judgeLifecyclePayload(HearingCourtAgentResult)」。
    // 具体功能：「HearingCourtOrchestrator.judgeLifecyclePayload(HearingCourtAgentResult)」：构建法官生命周期载荷；实际协作者为 「result.roundNo」、「result.nextRoundNo」、「result.finalDraftRequired」、「result.roundSummary」；处理的关键状态/协议值包括 「round_no」、「next_round_no」、「final_draft_required」、「round_summary」，最终返回「Map<String, Object>」。
    // 上游调用：「HearingCourtOrchestrator.judgeLifecyclePayload(HearingCourtAgentResult)」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.judgeLifecyclePayload(HearingCourtAgentResult)」向下依次触达 「result.roundNo」、「result.nextRoundNo」、「result.finalDraftRequired」、「result.roundSummary」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.judgeLifecyclePayload(HearingCourtAgentResult)」负责主链路中的“法官生命周期载荷”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private Map<String, Object> judgeLifecyclePayload(HearingCourtAgentResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round_no", result.roundNo());
        payload.put(
                "next_round_no",
                result.nextRoundNo() == null ? "" : result.nextRoundNo());
        payload.put("final_draft_required", result.finalDraftRequired());
        payload.put("round_summary", result.roundSummary());
        payload.put("questions_for_user", result.questionsForUser());
        payload.put("questions_for_merchant", result.questionsForMerchant());
        payload.put("review_focus_signal", result.reviewFocusSignal());
        if (result.finalProposedResolution() != null) {
            payload.put(
                    "final_proposed_resolution",
                    result.finalProposedResolution());
        }
        payload.put("prompt_version", result.promptVersion());
        payload.put("model", result.model());
        return Map.copyOf(payload);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.command(FulfillmentCaseEntity,HearingRoundEntity,List,boolean)」。
    // 具体功能：「HearingCourtOrchestrator.command(FulfillmentCaseEntity,HearingRoundEntity,List,boolean)」：构建命令；实际协作者为 「dispute.getId」、「dispute.getCurrentWorkflowId」、「dispute.getOrderId」、「dispute.getAfterSaleId」；处理的关键状态/协议值包括 「hearing-window-」、「MEDIUM」、「{}」，最终返回「HearingCourtAgentCommand」。
    // 上游调用：「HearingCourtOrchestrator.command(FulfillmentCaseEntity,HearingRoundEntity,List,boolean)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.command(FulfillmentCaseEntity,HearingRoundEntity,List,boolean)」向下依次触达 「dispute.getId」、「dispute.getCurrentWorkflowId」、「dispute.getOrderId」、「dispute.getAfterSaleId」；计算结果以「HearingCourtAgentCommand」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.command(FulfillmentCaseEntity,HearingRoundEntity,List,boolean)」负责主链路中的“命令”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private HearingCourtAgentCommand command(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity round,
            List<HearingRoundPartySubmissionEntity> submissions,
            boolean finalRound) {
        return new HearingCourtAgentCommand(
                dispute.getId(),
                defaultText(dispute.getCurrentWorkflowId(), "hearing-window-" + dispute.getId()),
                dispute.getOrderId(),
                dispute.getAfterSaleId(),
                dispute.getLogisticsId(),
                dispute.getDisputeType(),
                dispute.getTitle(),
                dispute.getDescription(),
                dispute.getRiskLevel() == null ? "MEDIUM" : dispute.getRiskLevel().name(),
                round.getRoundNo(),
                round.getDossierVersion(),
                finalRound,
                round.getRoundStatus().name(),
                round.getStopReason() == null ? null : round.getStopReason().name(),
                defaultText(round.getSummaryJson(), "{}"),
                courtroomContextJson(dispute.getId(), round.getRoundNo()),
                submissions.stream()
                        .map(
                                submission ->
                                        new HearingCourtAgentCommand.PartySubmission(
                                                submission.getParticipantRole().name(),
                                                participantId(dispute, submission.getParticipantRole()),
                                                submission.getSubmissionSource().name(),
                                                defaultText(submission.getSubmissionJson(), "{}")))
                        .toList());
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.courtroomContextJson(String,int)」。
    // 具体功能：「HearingCourtOrchestrator.courtroomContextJson(String,int)」：构建法庭上下文上下文JSON；实际协作者为 「courtroomContextAssembler.assemble」、「json」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.courtroomContextJson(String,int)」的上游调用点包括 「HearingCourtOrchestrator.command」。
    // 下游影响：「HearingCourtOrchestrator.courtroomContextJson(String,int)」向下依次触达 「courtroomContextAssembler.assemble」、「json」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.courtroomContextJson(String,int)」负责主链路中的“法庭上下文上下文JSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String courtroomContextJson(String caseId, int roundNo) {
        return json(courtroomContextAssembler.assemble(caseId, roundNo));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.judgeRequest(HearingCourtAgentCommand)」。
    // 具体功能：「HearingCourtOrchestrator.judgeRequest(HearingCourtAgentCommand)」：解析法官请求：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.valueToTree」、「objectMapper.readTree」、「command.courtroomContextJson」、「courtroomContext.isObject」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「courtroom_context_json」、「{}」、「courtroom_context」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtOrchestrator.judgeRequest(HearingCourtAgentCommand)」的上游调用点包括 「HearingCourtOrchestrator.startStreamingJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.judgeRequest(HearingCourtAgentCommand)」向下依次触达 「objectMapper.valueToTree」、「objectMapper.readTree」、「command.courtroomContextJson」、「courtroomContext.isObject」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.judgeRequest(HearingCourtAgentCommand)」负责主链路中的“法官请求”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode judgeRequest(HearingCourtAgentCommand command) {
        ObjectNode request = objectMapper.valueToTree(command);
        request.remove("courtroom_context_json");
        try {
            JsonNode courtroomContext =
                    objectMapper.readTree(
                            command.courtroomContextJson() == null
                                            || command.courtroomContextJson().isBlank()
                                    ? "{}"
                                    : command.courtroomContextJson());
            request.set(
                    "courtroom_context",
                    courtroomContext != null && courtroomContext.isObject()
                            ? courtroomContext
                            : objectMapper.createObjectNode());
        } catch (JsonProcessingException invalidContext) {
            throw new IllegalStateException("invalid courtroom context", invalidContext);
        }
        return request;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.safeGenerate(HearingCourtAgentCommand,String)」。
    // 具体功能：「HearingCourtOrchestrator.safeGenerate(HearingCourtAgentCommand,String)」：调用庭审 Agent 生成本轮法官发言；模型不可用时记录 case/round/Trace 并使用确定性 fallback，保证庭审时钟不会无限等待，最终返回「HearingCourtAgentResult」。
    // 上游调用：「HearingCourtOrchestrator.safeGenerate(HearingCourtAgentCommand,String)」的上游调用点包括 「HearingCourtOrchestrator.processJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.safeGenerate(HearingCourtAgentCommand,String)」向下依次触达 「agentClient.generateRoundTurn」、「command.caseId」、「command.roundNo」、「log.warn」；计算结果以「HearingCourtAgentResult」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.safeGenerate(HearingCourtAgentCommand,String)」负责主链路中的“Generate”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private HearingCourtAgentResult safeGenerate(HearingCourtAgentCommand command, String traceId) {
        try {
            return agentClient.generateRoundTurn(
                    command,
                    traceId,
                    "REQ_HEARING_ROUND_" + command.caseId() + "_" + command.roundNo());
        } catch (RuntimeException failure) {
            log.warn(
                    "Hearing court round turn degraded: case_id={}, round_no={}, trace_id={}",
                    command.caseId(),
                    command.roundNo(),
                    traceId,
                    failure);
            return fallback(command);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.fallback(HearingCourtAgentCommand)」。
    // 具体功能：「HearingCourtOrchestrator.fallback(HearingCourtAgentCommand)」：根据是否开场、是否最终轮和双方提交生成确定性法官话术；最终轮明确要求生成可审核草案，非最终轮只提出下一轮问题，最终返回「HearingCourtAgentResult」。
    // 上游调用：「HearingCourtOrchestrator.fallback(HearingCourtAgentCommand)」的上游调用点包括 「HearingCourtOrchestrator.safeGenerate」。
    // 下游影响：「HearingCourtOrchestrator.fallback(HearingCourtAgentCommand)」向下依次触达 「command.partySubmissions」、「command.roundStatus」、「command.stopReason」、「command.finalRound」；计算结果以「HearingCourtAgentResult」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.fallback(HearingCourtAgentCommand)」负责主链路中的“兜底结果”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private HearingCourtAgentResult fallback(HearingCourtAgentCommand command) {
        boolean opening =
                command.partySubmissions().isEmpty()
                        && "OPEN".equals(command.roundStatus())
                        && command.stopReason() == null;
        boolean finalRound = command.finalRound();
        if (opening) {
            return new HearingCourtAgentResult(
                    JUDGE_SENDER_ROLE,
                    "小法庭现在开庭。第 1 轮请双方先围绕接待室卷宗和证据室材料说明关键事实：用户侧请说明争议发生经过、签收或验货情况以及希望平台核验的重点；商家侧请说明履约记录、发货/物流交接情况以及与用户主张不一致的部分。",
                    "法官已打开第 1 轮事实陈述，等待用户和商家分别提交本轮说明。",
                    List.of("请说明争议发生经过、签收或验货情况，以及希望平台优先核验的事实。"),
                    List.of("请说明履约记录、发货/物流交接情况，以及与用户主张不一致的事实。"),
                    "JUDGE_OPENING_READY",
                    command.roundNo(),
                    command.roundNo(),
                    false,
                    "hearing-round-opening-fallback-v1",
                    "local-fallback");
        }
        if (finalRound) {
            return new HearingCourtAgentResult(
                    JUDGE_SENDER_ROLE,
                    "第 3 轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，并进入裁决草案与后续确认路径。",
                    "模型暂不可用，系统已封存最终轮材料并进入裁决草案生成路径。",
                    List.of(),
                    List.of(),
                    "FINAL_DRAFT_REQUIRED",
                    command.roundNo(),
                    null,
                    true,
                    reviewFocusSignal(command),
                    "hearing-round-turn-fallback-v1",
                    "local-fallback");
        }
        return new HearingCourtAgentResult(
                JUDGE_SENDER_ROLE,
                "本轮庭审陈述已封存。下一轮将继续围绕争议焦点进行定向说明，双方可分别补充与本案事实和证据相关的陈述。",
                "模型暂不可用，系统已按结构化庭审流程封存本轮材料。",
                List.of("请围绕法官上一轮问题补充客观事实、证据来源和时间线。"),
                List.of("请围绕履约记录、证据来源和与用户主张的差异补充说明。"),
                "JUDGE_NEXT_QUESTIONS_READY",
                command.roundNo(),
                command.roundNo() + 1,
                false,
                List.of(),
                "hearing-round-turn-fallback-v1",
                "local-fallback");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.reviewFocusSignal(HearingCourtAgentCommand)」。
    // 具体功能：「HearingCourtOrchestrator.reviewFocusSignal(HearingCourtAgentCommand)」：构建审核关注点信号；实际协作者为 「command.finalRound」、「command.partySubmissions」、「limit」，最终返回「List<String>」。
    // 上游调用：「HearingCourtOrchestrator.reviewFocusSignal(HearingCourtAgentCommand)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingCourtOrchestrator.fallback」。
    // 下游影响：「HearingCourtOrchestrator.reviewFocusSignal(HearingCourtAgentCommand)」向下依次触达 「command.finalRound」、「command.partySubmissions」、「limit」；计算结果以「List<String>」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.reviewFocusSignal(HearingCourtAgentCommand)」负责主链路中的“审核关注点信号”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<String> reviewFocusSignal(HearingCourtAgentCommand command) {
        if (!command.finalRound()) {
            return List.of();
        }
        return command.partySubmissions().stream()
                .map(this::reviewFocusSignal)
                .filter(signal -> !signal.isBlank())
                .limit(20)
                .toList();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.reviewFocusSignal(List)」。
    // 具体功能：「HearingCourtOrchestrator.reviewFocusSignal(List)」：构建审核关注点信号；实际协作者为 「submission.getParticipantRole」、「submission.getSubmissionSource」、「submission.getSubmissionJson」、「defaultText」；处理的关键状态/协议值包括 「{}」，最终返回「List<String>」。
    // 上游调用：「HearingCourtOrchestrator.reviewFocusSignal(List)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingCourtOrchestrator.fallback」。
    // 下游影响：「HearingCourtOrchestrator.reviewFocusSignal(List)」向下依次触达 「submission.getParticipantRole」、「submission.getSubmissionSource」、「submission.getSubmissionJson」、「defaultText」；计算结果以「List<String>」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.reviewFocusSignal(List)」负责主链路中的“审核关注点信号”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<String> reviewFocusSignal(
            List<HearingRoundPartySubmissionEntity> submissions) {
        return submissions.stream()
                .map(
                        submission ->
                                new HearingCourtAgentCommand.PartySubmission(
                                        submission.getParticipantRole().name(),
                                        "",
                                        submission.getSubmissionSource().name(),
                                        defaultText(submission.getSubmissionJson(), "{}")))
                .map(this::reviewFocusSignal)
                .filter(signal -> !signal.isBlank())
                .limit(20)
                .toList();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.reviewFocusSignal(PartySubmission)」。
    // 具体功能：「HearingCourtOrchestrator.reviewFocusSignal(PartySubmission)」：构建审核关注点信号；实际协作者为 「submission.submissionJson」、「submission.participantRole」、「statementFromSubmissionJson」、「thirdPersonReviewFocus」；处理的关键状态/协议值包括 「USER」、「认可」、「退款」、「签收人身份」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.reviewFocusSignal(PartySubmission)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingCourtOrchestrator.fallback」。
    // 下游影响：「HearingCourtOrchestrator.reviewFocusSignal(PartySubmission)」向下依次触达 「submission.submissionJson」、「submission.participantRole」、「statementFromSubmissionJson」、「thirdPersonReviewFocus」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.reviewFocusSignal(PartySubmission)」负责主链路中的“审核关注点信号”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String reviewFocusSignal(HearingCourtAgentCommand.PartySubmission submission) {
        String statement = statementFromSubmissionJson(submission.submissionJson());
        if (statement.isBlank()) {
            return "";
        }
        String role = submission.participantRole() == null ? "" : submission.participantRole();
        if ("USER".equalsIgnoreCase(role)) {
            if (statement.contains("认可")
                    && statement.contains("退款")
                    && statement.contains("签收人身份")) {
                return "用户认可退款方向，但要求复核签收人身份是否已核验清楚。";
            }
            return thirdPersonReviewFocus("用户", statement);
        }
        if ("MERCHANT".equalsIgnoreCase(role)) {
            if (statement.contains("不同意退款") && statement.contains("物流签收")) {
                return "商家不同意退款，主张物流签收记录足以证明已履约。";
            }
            return thirdPersonReviewFocus("商家", statement);
        }
        return thirdPersonReviewFocus("当事人", statement);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.statementFromSubmissionJson(String)」。
    // 具体功能：「HearingCourtOrchestrator.statementFromSubmissionJson(String)」：解析statementFrom提交JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「node.isObject」、「defaultText」、「node.path(field).asText」；处理的关键状态/协议值包括 「{}」、「statement」、「content」、「message」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.statementFromSubmissionJson(String)」的上游调用点包括 「HearingCourtOrchestrator.reviewFocusSignal」。
    // 下游影响：「HearingCourtOrchestrator.statementFromSubmissionJson(String)」向下依次触达 「objectMapper.readTree」、「node.isObject」、「defaultText」、「node.path(field).asText」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.statementFromSubmissionJson(String)」负责主链路中的“statementFrom提交JSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String statementFromSubmissionJson(String submissionJson) {
        try {
            JsonNode node = objectMapper.readTree(defaultText(submissionJson, "{}"));
            if (node.isObject()) {
                for (String field : List.of("statement", "content", "message", "text")) {
                    String value = node.path(field).asText("");
                    if (!value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            return defaultText(submissionJson, "").trim();
        }
        return "";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.thirdPersonReviewFocus(String,String)」。
    // 具体功能：「HearingCourtOrchestrator.thirdPersonReviewFocus(String,String)」：构建thirdPerson审核关注点；实际协作者为 「statement.replace」、「replaceAll」、「replace」、「statement.replace("我方",roleLabel).replace」；处理的关键状态/协议值包括 「我方」、「我们」、「我」、「[。；;\\s]+$」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.thirdPersonReviewFocus(String,String)」的上游调用点包括 「HearingCourtOrchestrator.reviewFocusSignal」。
    // 下游影响：「HearingCourtOrchestrator.thirdPersonReviewFocus(String,String)」向下依次触达 「statement.replace」、「replaceAll」、「replace」、「statement.replace("我方",roleLabel).replace」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.thirdPersonReviewFocus(String,String)」负责主链路中的“thirdPerson审核关注点”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String thirdPersonReviewFocus(String roleLabel, String statement) {
        String normalized =
                statement
                        .replace("我方", roleLabel)
                        .replace("我们", roleLabel)
                        .replace("我", roleLabel)
                        .replaceAll("[。；;\\s]+$", "");
        if (!normalized.startsWith(roleLabel)) {
            normalized = roleLabel + "提出：" + normalized;
        }
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 180);
        }
        return normalized + "。";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded(FulfillmentCaseEntity,CaseRoomEntity,int,String,List,String,String)」。
    // 具体功能：最终轮写入正式评审报告；优先复用 A2A 已有报告，缺失时只接受统一评审 Agent 生成并通过六项指标校验的报告，不再由 Java 生成固定模板。
    // 上游调用：「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded(FulfillmentCaseEntity,CaseRoomEntity,int,String,List,String,String)」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded(FulfillmentCaseEntity,CaseRoomEntity,int,String,List,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「a2aMessageService.findFormalJuryReviewReport」、「a2aMessageService.record」、「messageRepository.findMaxSequenceByRoomId」。
    // 系统意义：「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded(FulfillmentCaseEntity,CaseRoomEntity,int,String,List,String,String)」负责主链路中的“FormalJuryReportIfNeeded”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void appendFormalJuryReportIfNeeded(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            int roundNo,
            String judgeEventType,
            List<String> reviewFocusSignal,
            String finalProposedResolution,
            Map<String, Object> juryReviewReport,
            String promptVersion,
            RoomMessageEntity persistedJudgeProposal,
            String traceId) {
        if (roundNo < 3) {
            return;
        }
        String idempotencyKey = juryReviewReportKey(dispute.getId(), roundNo);
        Optional<RoomMessageEntity> roomReport =
                messageRepository
                        .findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey);
        Optional<AgentA2AMessageView> a2aReport =
                a2aMessageService.findFormalJuryReviewReport(dispute.getId(), roundNo);
        if (persistedJudgeProposal == null) {
            throw new IllegalStateException(
                    "formal jury review requires the persisted judge proposal");
        }

        Map<String, Object> persistedInputRefs =
                a2aReport
                        .map(
                                report ->
                                        jsonObject(
                                                report.inputRefsJson(),
                                                "formal jury A2A input refs"))
                        .orElseGet(LinkedHashMap::new);
        Map<String, Object> persistedPayload =
                a2aReport
                        .map(
                                report ->
                                        jsonObject(
                                                report.payloadJson(),
                                                "formal jury A2A payload"))
                        .orElseGet(
                                () ->
                                        roomReport
                                                .map(
                                                        message ->
                                                                jsonObject(
                                                                        message.getMessageText(),
                                                                        "jury room report"))
                                                .orElseGet(
                                                        () ->
                                                                juryReviewPayload(
                                                                        juryReviewReport,
                                                                        finalProposedResolution,
                                                                        reviewFocusSignal)));

        String reviewedProposal = requiredText(persistedPayload, "reviewed_proposal");
        if (persistedInputRefs.isEmpty()) {
            persistedInputRefs =
                    juryReviewInputRefs(
                            roundNo,
                            judgeEventType,
                            reviewFocusSignal,
                            promptVersion,
                            reviewedProposal);
        }
        validateFormalJuryArtifacts(
                persistedInputRefs,
                persistedPayload,
                persistedJudgeProposal.getMessageText());

        Map<String, Object> finalInputRefs = Map.copyOf(persistedInputRefs);
        Map<String, Object> finalPayload = Map.copyOf(persistedPayload);
        AgentA2AMessageView survivingA2A =
                a2aReport.orElseGet(
                        () ->
                                a2aMessageService.record(
                                        new AgentA2ACommand(
                                                dispute.getId(),
                                                roundNo,
                                                "JURY_PANEL",
                                                AgentA2AMessageService.PRESIDING_JUDGE,
                                                "JURY_REVIEW_REPORT",
                                                finalInputRefs,
                                                finalPayload,
                                                "REVIEWER_VISIBLE",
                                                null)));
        if (roomReport.isPresent()) {
            return;
        }
        persistedInputRefs =
                jsonObject(survivingA2A.inputRefsJson(), "formal jury A2A input refs");
        persistedPayload =
                jsonObject(survivingA2A.payloadJson(), "formal jury A2A payload");
        validateFormalJuryArtifacts(
                persistedInputRefs,
                persistedPayload,
                persistedJudgeProposal.getMessageText());
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity saved =
                messageRepository.save(
                        RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                "JURY",
                                "jury-panel",
                                sharedCourtAudienceJson(),
                                "[]",
                                MessageType.JURY_REVIEW_REPORT,
                                survivingA2A.payloadJson(),
                                "[]",
                                idempotencyKey,
                                roundNo,
                                Instant.now(clock),
                                traceId));
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                saved.getAudienceActorIdsJson(),
                "jury-panel");
        Object reviewFocus =
                persistedInputRefs.getOrDefault(
                        "review_focus_signal",
                        persistedPayload.getOrDefault(
                                "review_focus_signal", List.of()));
        Map<String, Object> lifecyclePayload = new LinkedHashMap<>();
        lifecyclePayload.put("round_no", roundNo);
        lifecyclePayload.put("visibility", survivingA2A.visibility());
        lifecyclePayload.put("review_focus_signal", reviewFocus);
        lifecyclePayload.put(
                "risk_level",
                persistedPayload.getOrDefault("risk_level", "MEDIUM"));
        lifecyclePayload.put(
                "confidence_score",
                persistedPayload.getOrDefault("confidence_score", 0));
        eventService.recordLifecycleEvent(
                dispute.getId(),
                room.getId(),
                "JURY_REVIEW_REPORT_READY",
                lifecyclePayload,
                "jury-review-report-ready:" + dispute.getId() + ":" + roundNo,
                "jury-panel");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.hasCompleteFormalJuryReport(String,int)」。
    // 具体功能：「HearingCourtOrchestrator.hasCompleteFormalJuryReport(String,int)」：判断是否存在协议完整性FormalJuryReport；实际协作者为 「messageRepository.findByCaseIdAndIdempotencyKey」、「a2aMessageService.hasFormalJuryReviewReport」、「juryReviewReportKey」、「isPresent」，最终返回「boolean」。
    // 上游调用：「HearingCourtOrchestrator.hasCompleteFormalJuryReport(String,int)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosed」、「HearingCourtOrchestrator.finalizeResult」、「HearingCourtOrchestrator.isJudgeTurnComplete」、「HearingCourtOrchestrator.prepareJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.hasCompleteFormalJuryReport(String,int)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「a2aMessageService.hasFormalJuryReviewReport」、「juryReviewReportKey」、「isPresent」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.hasCompleteFormalJuryReport(String,int)」负责主链路中的“协议完整性FormalJuryReport”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public boolean hasCompleteFormalJuryReport(String caseId, int roundNo) {
        Optional<RoomMessageEntity> roomReport =
                messageRepository.findByCaseIdAndIdempotencyKey(
                        caseId, juryReviewReportKey(caseId, roundNo));
        Optional<RoomMessageEntity> judgeMessage =
                messageRepository.findByCaseIdAndIdempotencyKey(
                        caseId, judgeRoundTurnKey(caseId, roundNo));
        Optional<AgentA2AMessageView> a2aReport =
                a2aMessageService.findFormalJuryReviewReport(caseId, roundNo);
        if (roomReport.isEmpty() || judgeMessage.isEmpty() || a2aReport.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> inputRefs =
                    jsonObject(a2aReport.get().inputRefsJson(), "formal jury A2A input refs");
            Map<String, Object> payload =
                    jsonObject(a2aReport.get().payloadJson(), "formal jury A2A payload");
            Map<String, Object> roomPayload =
                    jsonObject(roomReport.get().getMessageText(), "jury room report");
            validateFormalJuryArtifacts(
                    inputRefs, payload, judgeMessage.get().getMessageText());
            return payload.equals(roomPayload);
        } catch (RuntimeException invalidReport) {
            return false;
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.juryReviewPayload(List)」。
    // 具体功能：校验统一评审 Agent 的六维报告，覆盖模型越权标志，并把法官归一化后的第三轮复核方向写入正式 A2A 载荷。
    // 上游调用：「HearingCourtOrchestrator.juryReviewPayload(List)」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」。
    // 下游影响：「HearingCourtOrchestrator.juryReviewPayload(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.juryReviewPayload(List)」负责主链路中的“jury审核载荷”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private Map<String, Object> juryReviewPayload(
            Map<String, Object> juryReviewReport,
            String finalProposedResolution,
            List<String> reviewFocusSignal) {
        if (finalProposedResolution == null || finalProposedResolution.isBlank()) {
            throw new IllegalStateException(
                    "final hearing convergence requires a proposed resolution");
        }
        if (juryReviewReport == null || juryReviewReport.isEmpty()) {
            throw new IllegalStateException(
                    "final hearing convergence requires a model-generated jury review report");
        }
        Object findings = juryReviewReport.get("findings");
        if (!(findings instanceof List<?> findingList) || findingList.size() != 6) {
            throw new IllegalStateException(
                    "jury review report must contain all six evaluation dimensions");
        }
        if (!(juryReviewReport.get("summary") instanceof String summary)
                || summary.isBlank()
                || !(juryReviewReport.get("risk_level") instanceof String riskLevel)
                || riskLevel.isBlank()
                || !(juryReviewReport.get("confidence_score") instanceof Number)) {
            throw new IllegalStateException("jury review report is missing required fields");
        }
        if (Boolean.TRUE.equals(juryReviewReport.get("approval_performed"))
                || Boolean.TRUE.equals(juryReviewReport.get("execution_triggered"))
                || Boolean.TRUE.equals(juryReviewReport.get("is_final_decision"))) {
            throw new IllegalStateException(
                    "jury review report attempted approval, execution, or final adjudication");
        }
        String reviewedProposal = requiredText(juryReviewReport, "reviewed_proposal");
        if (!finalProposedResolution.equals(reviewedProposal)) {
            throw new IllegalStateException(
                    "jury review report does not review the judge's exact proposed resolution");
        }
        Map<String, Object> payload = new LinkedHashMap<>(juryReviewReport);
        payload.put("final_proposed_resolution", finalProposedResolution);
        payload.put(
                "review_focus_signal",
                reviewFocusSignal == null ? List.of() : List.copyOf(reviewFocusSignal));
        payload.put("visibility", "REVIEWER_VISIBLE");
        payload.put("approval_performed", false);
        payload.put("execution_triggered", false);
        payload.put("is_final_decision", false);
        return Map.copyOf(payload);
    }

    private Map<String, Object> juryReviewInputRefs(
            int roundNo,
            String judgeEventType,
            List<String> reviewFocusSignal,
            String promptVersion,
            String finalProposedResolution) {
        Map<String, Object> inputRefs = new LinkedHashMap<>();
        inputRefs.put("round_no", roundNo);
        inputRefs.put("judge_event_type", judgeEventType);
        inputRefs.put(
                "review_focus_signal",
                reviewFocusSignal == null ? List.of() : List.copyOf(reviewFocusSignal));
        inputRefs.put("prompt_version", promptVersion);
        inputRefs.put("final_proposed_resolution", finalProposedResolution);
        return Map.copyOf(inputRefs);
    }

    private void validateFormalJuryArtifacts(
            Map<String, Object> inputRefs,
            Map<String, Object> payload,
            String judgeMessageText) {
        String proposedResolution =
                requiredText(inputRefs, "final_proposed_resolution");
        String reviewedProposal = requiredText(payload, "reviewed_proposal");
        String payloadProposal = requiredText(payload, "final_proposed_resolution");
        if (!proposedResolution.equals(reviewedProposal)
                || !proposedResolution.equals(payloadProposal)) {
            throw new IllegalStateException(
                    "formal jury review proposal references are inconsistent");
        }
        if (judgeMessageText == null || !judgeMessageText.contains(proposedResolution)) {
            throw new IllegalStateException(
                    "persisted judge message does not contain the reviewed proposal");
        }

        Object findings = payload.get("findings");
        if (!(findings instanceof List<?> findingList) || findingList.size() != 6) {
            throw new IllegalStateException(
                    "jury review report must contain all six evaluation dimensions");
        }
        Set<String> dimensions =
                findingList.stream()
                        .map(
                                finding -> {
                                    if (!(finding instanceof Map<?, ?> findingMap)) {
                                        throw new IllegalStateException(
                                                "jury review finding must be an object");
                                    }
                                    Object dimension = findingMap.get("dimension");
                                    if (!(dimension instanceof String value) || value.isBlank()) {
                                        throw new IllegalStateException(
                                                "jury review finding is missing its dimension");
                                    }
                                    return value;
                                })
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!dimensions.equals(REQUIRED_JURY_REVIEW_DIMENSIONS)) {
            throw new IllegalStateException(
                    "jury review report must cover the six required dimensions exactly once");
        }
        if (!(payload.get("summary") instanceof String summary)
                || summary.isBlank()
                || !(payload.get("risk_level") instanceof String riskLevel)
                || riskLevel.isBlank()
                || !(payload.get("confidence_score") instanceof Number)) {
            throw new IllegalStateException("jury review report is missing required fields");
        }
        if (Boolean.TRUE.equals(payload.get("approval_performed"))
                || Boolean.TRUE.equals(payload.get("execution_triggered"))
                || Boolean.TRUE.equals(payload.get("is_final_decision"))) {
            throw new IllegalStateException(
                    "jury review report attempted approval, execution, or final adjudication");
        }
    }

    private static String requiredText(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("jury review report is missing " + field);
        }
        return text;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.jsonObject(String,String)」。
    // 具体功能：「HearingCourtOrchestrator.jsonObject(String,String)」：解析JSON对象：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「defaultText」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「{}」，最终返回「Map<String, Object>」。
    // 上游调用：「HearingCourtOrchestrator.jsonObject(String,String)」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」。
    // 下游影响：「HearingCourtOrchestrator.jsonObject(String,String)」向下依次触达 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「defaultText」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.jsonObject(String,String)」统一“JSON对象”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private Map<String, Object> jsonObject(String value, String label) {
        try {
            JsonNode node = objectMapper.readTree(defaultText(value, "{}"));
            if (!node.isObject()) {
                throw new IllegalStateException(label + " must be a JSON object");
            }
            return objectMapper.convertValue(
                    node, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid " + label, exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.appendJudgeMessage(FulfillmentCaseEntity,CaseRoomEntity,int,HearingCourtAgentResult,String,String,String)」。
    // 具体功能：「HearingCourtOrchestrator.appendJudgeMessage(FulfillmentCaseEntity,CaseRoomEntity,int,HearingCourtAgentResult,String,String,String)」：追加法官消息：先把新状态写入 PostgreSQL 事实表；实际协作者为 「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「RoomMessageEntity.create」、「room.getId」；处理的关键状态/协议值包括 「MESSAGE_」、「[]」，最终返回「RoomMessageEntity」。
    // 上游调用：「HearingCourtOrchestrator.appendJudgeMessage(FulfillmentCaseEntity,CaseRoomEntity,int,HearingCourtAgentResult,String,String,String)」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「HearingCourtOrchestrator.appendJudgeMessage(FulfillmentCaseEntity,CaseRoomEntity,int,HearingCourtAgentResult,String,String,String)」向下依次触达 「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「RoomMessageEntity.create」、「room.getId」；计算结果以「RoomMessageEntity」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.appendJudgeMessage(FulfillmentCaseEntity,CaseRoomEntity,int,HearingCourtAgentResult,String,String,String)」负责主链路中的“法官消息”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private RoomMessageEntity appendJudgeMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            int roundNo,
            HearingCourtAgentResult result,
            String idempotencyKey,
            String traceId,
            String runId) {
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity message =
                RoomMessageEntity.create(
                        "MESSAGE_" + compactUuid(),
                        dispute.getId(),
                        room.getId(),
                        sequence,
                        MessageSenderType.AGENT,
                        JUDGE_SENDER_ROLE,
                        JUDGE_SENDER_ID,
                        sharedCourtAudienceJson(),
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        result.messageText(),
                        "[]",
                        idempotencyKey,
                        roundNo,
                        Instant.now(clock),
                        traceId);
        message.attachAgentRun(runId);
        return messageRepository.save(message);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.sharedCourtAudienceJson()」。
    // 具体功能：「HearingCourtOrchestrator.sharedCourtAudienceJson()」：计算 SHA-shared法庭受众 JSONJSON；实际协作者为 「json」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.sharedCourtAudienceJson()」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.appendJudgeMessage」。
    // 下游影响：「HearingCourtOrchestrator.sharedCourtAudienceJson()」向下依次触达 「json」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.sharedCourtAudienceJson()」负责主链路中的“shared法庭受众 JSONJSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String sharedCourtAudienceJson() {
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.json(Object)」。
    // 具体功能：「HearingCourtOrchestrator.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.json(Object)」的上游调用点包括 「HearingCourtOrchestrator.courtroomContextJson」、「HearingCourtOrchestrator.sharedCourtAudienceJson」。
    // 下游影响：「HearingCourtOrchestrator.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize hearing court payload", exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.participantId(FulfillmentCaseEntity,ActorRole)」。
    // 具体功能：「HearingCourtOrchestrator.participantId(FulfillmentCaseEntity,ActorRole)」：构建参与人标识；实际协作者为 「dispute.getUserId」、「dispute.getMerchantId」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.participantId(FulfillmentCaseEntity,ActorRole)」的上游调用点包括 「HearingCourtOrchestrator.command」。
    // 下游影响：「HearingCourtOrchestrator.participantId(FulfillmentCaseEntity,ActorRole)」向下依次触达 「dispute.getUserId」、「dispute.getMerchantId」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.participantId(FulfillmentCaseEntity,ActorRole)」负责主链路中的“参与人标识”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String participantId(FulfillmentCaseEntity dispute, ActorRole role) {
        return switch (role) {
            case USER -> dispute.getUserId();
            case MERCHANT -> dispute.getMerchantId();
            default -> role.name();
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.defaultText(String,String)」。
    // 具体功能：「HearingCourtOrchestrator.defaultText(String,String)」：构建默认文本，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.defaultText(String,String)」的上游调用点包括 「HearingCourtOrchestrator.command」、「HearingCourtOrchestrator.reviewFocusSignal」、「HearingCourtOrchestrator.statementFromSubmissionJson」、「HearingCourtOrchestrator.jsonObject」。
    // 下游影响：「HearingCourtOrchestrator.defaultText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.defaultText(String,String)」负责主链路中的“默认文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.judgeRoundOpeningKey(String,int)」。
    // 具体功能：「HearingCourtOrchestrator.judgeRoundOpeningKey(String,int)」：构建法官轮次开场消息键；处理的关键状态/协议值包括 「judge-round-opening:」、「:」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.judgeRoundOpeningKey(String,int)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundOpened」。
    // 下游影响：「HearingCourtOrchestrator.judgeRoundOpeningKey(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.judgeRoundOpeningKey(String,int)」负责主链路中的“法官轮次开场消息键”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String judgeRoundOpeningKey(String caseId, int roundNo) {
        return "judge-round-opening:" + caseId + ":" + roundNo;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.judgeRoundTurnKey(String,int)」。
    // 具体功能：「HearingCourtOrchestrator.judgeRoundTurnKey(String,int)」：构建法官轮次轮次键；处理的关键状态/协议值包括 「judge-round-turn:」、「:」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.judgeRoundTurnKey(String,int)」的上游调用点包括 「HearingCourtOrchestrator.afterRoundClosed」、「HearingCourtOrchestrator.isJudgeTurnComplete」。
    // 下游影响：「HearingCourtOrchestrator.judgeRoundTurnKey(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.judgeRoundTurnKey(String,int)」负责主链路中的“法官轮次轮次键”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String judgeRoundTurnKey(String caseId, int roundNo) {
        return "judge-round-turn:" + caseId + ":" + roundNo;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.juryReviewReportKey(String,int)」。
    // 具体功能：「HearingCourtOrchestrator.juryReviewReportKey(String,int)」：构建jury审核Report键；处理的关键状态/协议值包括 「jury-review-report:」、「:」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.juryReviewReportKey(String,int)」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.hasCompleteFormalJuryReport」。
    // 下游影响：「HearingCourtOrchestrator.juryReviewReportKey(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.juryReviewReportKey(String,int)」负责主链路中的“jury审核Report键”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String juryReviewReportKey(String caseId, int roundNo) {
        return "jury-review-report:" + caseId + ":" + roundNo;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.compactUuid()」。
    // 具体功能：「HearingCourtOrchestrator.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「HearingCourtOrchestrator.compactUuid()」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.appendJudgeMessage」。
    // 下游影响：「HearingCourtOrchestrator.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtOrchestrator.compactUuid()」负责主链路中的“UUID”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【共享小法庭 / 应用编排层】类型「TurnPreparation」。
    // 类型职责：定义轮次Preparation跨层传递时使用的不可变数据契约；本类型显式提供 「completed」、「repair」、「generate」。
    // 协作关系：主要由 「HearingCourtOrchestrator.finalizeResult」、「HearingCourtOrchestrator.prepareJudgeTurn」 使用。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record TurnPreparation(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey,
            HearingCourtAgentCommand command,
            List<String> recoveryReviewFocus,
            boolean complete) {

        // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.TurnPreparation.completed(String,int,boolean,String,String)」。
        // 具体功能：「HearingCourtOrchestrator.TurnPreparation.completed(String,int,boolean,String,String)」：完成完成，最终返回「TurnPreparation」。
        // 上游调用：「HearingCourtOrchestrator.TurnPreparation.completed(String,int,boolean,String,String)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」。
        // 下游影响：「HearingCourtOrchestrator.TurnPreparation.completed(String,int,boolean,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「TurnPreparation」交给调用方。
        // 系统意义：「HearingCourtOrchestrator.TurnPreparation.completed(String,int,boolean,String,String)」负责主链路中的“完成”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static TurnPreparation completed(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    null,
                    List.of(),
                    true);
        }

        // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.TurnPreparation.repair(String,int,boolean,String,String,List)」。
        // 具体功能：「HearingCourtOrchestrator.TurnPreparation.repair(String,int,boolean,String,String,List)」：构建恢复修复，最终返回「TurnPreparation」。
        // 上游调用：「HearingCourtOrchestrator.TurnPreparation.repair(String,int,boolean,String,String,List)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」。
        // 下游影响：「HearingCourtOrchestrator.TurnPreparation.repair(String,int,boolean,String,String,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「TurnPreparation」交给调用方。
        // 系统意义：「HearingCourtOrchestrator.TurnPreparation.repair(String,int,boolean,String,String,List)」负责主链路中的“恢复修复”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static TurnPreparation repair(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey,
                HearingCourtAgentCommand command,
                List<String> recoveryReviewFocus) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    command,
                    List.copyOf(recoveryReviewFocus),
                    false);
        }

        // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtOrchestrator.TurnPreparation.generate(String,int,boolean,String,String,HearingCourtAgentCommand)」。
        // 具体功能：「HearingCourtOrchestrator.TurnPreparation.generate(String,int,boolean,String,String,HearingCourtAgentCommand)」：生成轮次Preparation，最终返回「TurnPreparation」。
        // 上游调用：「HearingCourtOrchestrator.TurnPreparation.generate(String,int,boolean,String,String,HearingCourtAgentCommand)」的上游调用点包括 「HearingCourtOrchestrator.finalizeResult」、「HearingCourtOrchestrator.prepareJudgeTurn」。
        // 下游影响：「HearingCourtOrchestrator.TurnPreparation.generate(String,int,boolean,String,String,HearingCourtAgentCommand)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「TurnPreparation」交给调用方。
        // 系统意义：「HearingCourtOrchestrator.TurnPreparation.generate(String,int,boolean,String,String,HearingCourtAgentCommand)」负责主链路中的“轮次Preparation”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static TurnPreparation generate(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey,
                HearingCourtAgentCommand command) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    command,
                    List.of(),
                    false);
        }
    }
}
