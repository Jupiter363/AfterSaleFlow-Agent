/*
 * 所属模块：共享小法庭。
 * 文件职责：编排AgentA2A消息规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「record」、「findForJudge」、「hasFormalJuryReviewReport」、「findFormalJuryReviewReport」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.AgentA2AMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【共享小法庭 / 应用编排层】类型「AgentA2AMessageService」。
// 类型职责：编排AgentA2A消息规则、权限校验与事实读写；本类型显式提供 「AgentA2AMessageService」、「record」、「findForJudge」、「hasFormalJuryReviewReport」、「findFormalJuryReviewReport」、「json」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.attachJuryContext」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.hasCompleteFormalJuryReport」、「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentA2AMessageService {

    public static final String PRESIDING_JUDGE = "PRESIDING_JUDGE";

    private final AgentA2AMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.AgentA2AMessageService(AgentA2AMessageRepository,ObjectMapper,Clock)」。
    // 具体功能：「AgentA2AMessageService.AgentA2AMessageService(AgentA2AMessageRepository,ObjectMapper,Clock)」：通过构造器接收 「repository」(AgentA2AMessageRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「AgentA2AMessageService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentA2AMessageService.AgentA2AMessageService(AgentA2AMessageRepository,ObjectMapper,Clock)」的上游创建点包括 「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound」、「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」、「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists」。
    // 下游影响：「AgentA2AMessageService.AgentA2AMessageService(AgentA2AMessageRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentA2AMessageService.AgentA2AMessageService(AgentA2AMessageRepository,ObjectMapper,Clock)」负责主链路中的“AgentA2A消息服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentA2AMessageService(
            AgentA2AMessageRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.record(AgentA2ACommand)」。
    // 具体功能：「AgentA2AMessageService.record(AgentA2ACommand)」：记录AgentA2A消息：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「repository.save」、「AgentA2AMessageEntity.create」、「command.caseId」、「command.roundNo」；处理的关键状态/协议值包括 「A2A_」，最终返回「AgentA2AMessageView」。
    // 上游调用：「AgentA2AMessageService.record(AgentA2ACommand)」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」、「HearingCourtOrchestratorTest.setUp」。
    // 下游影响：「AgentA2AMessageService.record(AgentA2ACommand)」向下依次触达 「repository.save」、「AgentA2AMessageEntity.create」、「command.caseId」、「command.roundNo」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentA2AMessageService.record(AgentA2ACommand)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentA2AMessageView record(AgentA2ACommand command) {
        AgentA2AMessageEntity saved =
                repository.save(
                        AgentA2AMessageEntity.create(
                                "A2A_" + compactUuid(),
                                command.caseId(),
                                command.roundNo(),
                                command.fromAgent(),
                                command.toAgent(),
                                command.messageType(),
                                json(command.inputRefs()),
                                json(command.payload()),
                                command.visibility(),
                                command.agentRunId(),
                                clock.instant(),
                                command.fromAgent()));
        return view(saved);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.findForJudge(String,int)」。
    // 具体功能：「AgentA2AMessageService.findForJudge(String,int)」：查找面向法官：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc」，最终返回「List<AgentA2AMessageView>」。
    // 上游调用：「AgentA2AMessageService.findForJudge(String,int)」的上游调用点包括 「ActiveCourtroomContextAssembler.attachJuryContext」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」。
    // 下游影响：「AgentA2AMessageService.findForJudge(String,int)」向下依次触达 「findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentA2AMessageService.findForJudge(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<AgentA2AMessageView> findForJudge(String caseId, int roundNo) {
        return repository
                .findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                        caseId,
                        PRESIDING_JUDGE,
                        roundNo)
                .stream()
                .map(AgentA2AMessageService::view)
                .toList();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.hasFormalJuryReviewReport(String,int)」。
    // 具体功能：「AgentA2AMessageService.hasFormalJuryReviewReport(String,int)」：判断是否存在FormalJury审核Report：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType」；处理的关键状态/协议值包括 「JURY_PANEL」、「JURY_REVIEW_REPORT」，最终返回「boolean」。
    // 上游调用：「AgentA2AMessageService.hasFormalJuryReviewReport(String,int)」的上游调用点包括 「HearingCourtOrchestrator.hasCompleteFormalJuryReport」、「HearingCourtOrchestratorTest.afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion」、「HearingCourtOrchestratorTest.finalRoundRetryRepairsMissingFormalJuryReportBeforeCompletion」、「HearingCourtOrchestratorTest.finalRoundRetryRepairsMissingA2AWhenTheJuryRoomMessageAlreadyExists」。
    // 下游影响：「AgentA2AMessageService.hasFormalJuryReviewReport(String,int)」向下依次触达 「repository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentA2AMessageService.hasFormalJuryReviewReport(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public boolean hasFormalJuryReviewReport(String caseId, int roundNo) {
        return repository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(
                caseId,
                roundNo,
                "JURY_PANEL",
                PRESIDING_JUDGE,
                "JURY_REVIEW_REPORT");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.findFormalJuryReviewReport(String,int)」。
    // 具体功能：「AgentA2AMessageService.findFormalJuryReviewReport(String,int)」：查找FormalJury审核Report：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc」；处理的关键状态/协议值包括 「JURY_PANEL」、「JURY_REVIEW_REPORT」，最终返回「Optional<AgentA2AMessageView>」。
    // 上游调用：「AgentA2AMessageService.findFormalJuryReviewReport(String,int)」的上游调用点包括 「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」、「HearingCourtOrchestratorTest.finalRoundRetryReusesTheSurvivingA2APayloadWhenRepairingTheRoomCard」。
    // 下游影响：「AgentA2AMessageService.findFormalJuryReviewReport(String,int)」向下依次触达 「findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentA2AMessageService.findFormalJuryReviewReport(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public Optional<AgentA2AMessageView> findFormalJuryReviewReport(
            String caseId, int roundNo) {
        return repository
                .findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(
                        caseId,
                        roundNo,
                        "JURY_PANEL",
                        PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT")
                .map(AgentA2AMessageService::view);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.json(Object)」。
    // 具体功能：「AgentA2AMessageService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「AgentA2AMessageService.json(Object)」的上游调用点包括 「AgentA2AMessageService.record」。
    // 下游影响：「AgentA2AMessageService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize A2A message", exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.view(AgentA2AMessageEntity)」。
    // 具体功能：「AgentA2AMessageService.view(AgentA2AMessageEntity)」：构建视图；实际协作者为 「entity.getId」、「entity.getCaseId」、「entity.getRoundNo」、「entity.getFromAgent」，最终返回「AgentA2AMessageView」。
    // 上游调用：「AgentA2AMessageService.view(AgentA2AMessageEntity)」的上游调用点包括 「AgentA2AMessageService.record」。
    // 下游影响：「AgentA2AMessageService.view(AgentA2AMessageEntity)」向下依次触达 「entity.getId」、「entity.getCaseId」、「entity.getRoundNo」、「entity.getFromAgent」；计算结果以「AgentA2AMessageView」交给调用方。
    // 系统意义：「AgentA2AMessageService.view(AgentA2AMessageEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static AgentA2AMessageView view(AgentA2AMessageEntity entity) {
        return new AgentA2AMessageView(
                entity.getId(),
                entity.getCaseId(),
                entity.getRoundNo(),
                entity.getFromAgent(),
                entity.getToAgent(),
                entity.getMessageType(),
                entity.getInputRefsJson(),
                entity.getPayloadJson(),
                entity.getVisibility(),
                entity.getAgentRunId(),
                entity.getCreatedAt());
    }

    // 所属模块：【共享小法庭 / 应用编排层】「AgentA2AMessageService.compactUuid()」。
    // 具体功能：「AgentA2AMessageService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AgentA2AMessageService.compactUuid()」的上游调用点包括 「AgentA2AMessageService.record」。
    // 下游影响：「AgentA2AMessageService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageService.compactUuid()」负责主链路中的“UUID”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
