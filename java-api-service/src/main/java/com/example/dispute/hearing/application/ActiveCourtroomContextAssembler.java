/*
 * 所属模块：共享小法庭。
 * 文件职责：承载当前法庭上下文上下文组装器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「assemble」、「assembleFinalConvergence」、「sealedRounds」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【共享小法庭 / 应用编排层】类型「ActiveCourtroomContextAssembler」。
// 类型职责：承载当前法庭上下文上下文组装器在当前业务模块中的规则与协作边界；本类型显式提供 「ActiveCourtroomContextAssembler」、「assemble」、「assembleFinalConvergence」、「sealedRounds」、「validatedSealedRounds」、「sealedRound」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「HearingCourtOrchestrator.courtroomContextJson」、「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ActiveCourtroomContextAssembler {

    private final HearingRecordRepository hearingRecordRepository;
    private final EvidenceDossierRepository evidenceDossierRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final AgentA2AMessageService a2aMessageService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.ActiveCourtroomContextAssembler(HearingRecordRepository,EvidenceDossierRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,AgentA2AMessageService,ToolRegistry,ObjectMapper)」。
    // 具体功能：「ActiveCourtroomContextAssembler.ActiveCourtroomContextAssembler(HearingRecordRepository,EvidenceDossierRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,AgentA2AMessageService,ToolRegistry,ObjectMapper)」：通过构造器接收 「hearingRecordRepository」(HearingRecordRepository)、「evidenceDossierRepository」(EvidenceDossierRepository)、「roundRepository」(HearingRoundRepository)、「submissionRepository」(HearingRoundPartySubmissionRepository)、「a2aMessageService」(AgentA2AMessageService)、「toolRegistry」(ToolRegistry)、「objectMapper」(ObjectMapper) 并保存为「ActiveCourtroomContextAssembler」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ActiveCourtroomContextAssembler.ActiveCourtroomContextAssembler(HearingRecordRepository,EvidenceDossierRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,AgentA2AMessageService,ToolRegistry,ObjectMapper)」的上游创建点包括 「ActiveCourtroomContextAssemblerTest.setUp」、「HearingCourtOrchestratorTest.setUp」、「HearingPersistenceIntegrationTest.activeContextAssembler」。
    // 下游影响：「ActiveCourtroomContextAssembler.ActiveCourtroomContextAssembler(HearingRecordRepository,EvidenceDossierRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,AgentA2AMessageService,ToolRegistry,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActiveCourtroomContextAssembler.ActiveCourtroomContextAssembler(HearingRecordRepository,EvidenceDossierRepository,HearingRoundRepository,HearingRoundPartySubmissionRepository,AgentA2AMessageService,ToolRegistry,ObjectMapper)」负责主链路中的“法庭上下文上下文组装器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ActiveCourtroomContextAssembler(
            HearingRecordRepository hearingRecordRepository,
            EvidenceDossierRepository evidenceDossierRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            AgentA2AMessageService a2aMessageService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this.hearingRecordRepository = hearingRecordRepository;
        this.evidenceDossierRepository = evidenceDossierRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.a2aMessageService = a2aMessageService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.assemble(String,int)」。
    // 具体功能：「ActiveCourtroomContextAssembler.assemble(String,int)」：组装对象节点：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc」、「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「record.getOutputJson」、「context.withObjectProperty」；处理的关键状态/协议值包括 「source_versions」、「evidence_dossier_version」、「evidence_dossier_ref」、「baseline_version」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.assemble(String,int)」的上游调用点包括 「ActiveCourtroomContextAssembler.assembleFinalConvergence」、「HearingCourtOrchestrator.courtroomContextJson」。
    // 下游影响：「ActiveCourtroomContextAssembler.assemble(String,int)」向下依次触达 「findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc」、「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「record.getOutputJson」、「context.withObjectProperty」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ActiveCourtroomContextAssembler.assemble(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public ObjectNode assemble(String caseId, int throughRoundNo) {
        ObjectNode context =
                hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                caseId,
                                HearingCourtBootstrapService.BOOTSTRAP_NODE,
                                HearingCourtBootstrapService.OPENING_ROUND_NO,
                                HearingCourtBootstrapService.SNAPSHOT_RECORD_TYPE)
                        .map(record -> readObject(record.getOutputJson(), "hearing bootstrap snapshot"))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing bootstrap snapshot not found for case "
                                                        + caseId));
        int baselineVersion =
                context.path("source_versions")
                        .path("evidence_dossier_version")
                        .asInt(context.path("evidence_dossier_version").asInt(0));
        var active = evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(caseId);
        int activeVersion =
                active.map(EvidenceDossierEntity::getDossierVersion).orElse(baselineVersion);
        if (baselineVersion <= 0) {
            baselineVersion = activeVersion;
        }

        ObjectNode sourceVersions = context.withObjectProperty("source_versions");
        sourceVersions.put("evidence_dossier_version", baselineVersion);
        ObjectNode ref = context.putObject("evidence_dossier_ref");
        ref.put("baseline_version", baselineVersion);
        ref.put("active_version", activeVersion);
        active.ifPresent(
                dossier -> {
                    ref.put("active_dossier_id", dossier.getId());
                    ref.put("active_status", dossier.getDossierStatus());
                    context.put("evidence_dossier_version", dossier.getDossierVersion());
                    context.set("evidence_dossier", evidenceDossierContext(dossier));
                });

        attachJuryContext(context, caseId, throughRoundNo);
        context.set("execution_tool_declarations", executionToolDeclarations());
        return context;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.assembleFinalConvergence(String,int)」。
    // 具体功能：「ActiveCourtroomContextAssembler.assembleFinalConvergence(String,int)」：组装终态Convergence：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「formalReport.isObject」、「assemble」、「validatedSealedRounds」、「formalReport.path("round_no").asInt」；不满足前置条件时抛出 「IllegalArgumentException」、「IllegalStateException」；处理的关键状态/协议值包括 「jury_review_report」、「round_no」、「JURY_REVIEW_REPORT」、「message_type」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.assembleFinalConvergence(String,int)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」。
    // 下游影响：「ActiveCourtroomContextAssembler.assembleFinalConvergence(String,int)」向下依次触达 「formalReport.isObject」、「assemble」、「validatedSealedRounds」、「formalReport.path("round_no").asInt」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ActiveCourtroomContextAssembler.assembleFinalConvergence(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public ObjectNode assembleFinalConvergence(String caseId, int throughRoundNo) {
        if (throughRoundNo < 1) {
            throw new IllegalArgumentException(
                    "final convergence round number must be positive");
        }
        ObjectNode context = assemble(caseId, throughRoundNo);
        JsonNode formalReport = context.path("jury_review_report");
        if (!formalReport.isObject()) {
            throw new IllegalStateException(
                    "formal jury review report for round "
                            + throughRoundNo
                            + " is required for final convergence");
        }
        if (formalReport.path("round_no").asInt(-1) != throughRoundNo) {
            throw new IllegalStateException(
                    "formal jury review report for round "
                            + throughRoundNo
                            + " is required for final convergence");
        }
        if (!"JURY_REVIEW_REPORT".equals(
                        formalReport.path("message_type").asText())
                || !"JURY_PANEL".equals(formalReport.path("from_agent").asText())
                || !AgentA2AMessageService.PRESIDING_JUDGE.equals(
                        formalReport.path("to_agent").asText())) {
            throw new IllegalStateException(
                    "formal jury review report must be sent from JURY_PANEL to PRESIDING_JUDGE");
        }
        validatedSealedRounds(caseId, throughRoundNo);
        return context;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.sealedRounds(String,int)」。
    // 具体功能：「ActiveCourtroomContextAssembler.sealedRounds(String,int)」：构建已封存Rounds：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「validatedSealedRounds」，最终返回「ArrayNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.sealedRounds(String,int)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory」。
    // 下游影响：「ActiveCourtroomContextAssembler.sealedRounds(String,int)」向下依次触达 「validatedSealedRounds」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ActiveCourtroomContextAssembler.sealedRounds(String,int)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public ArrayNode sealedRounds(String caseId, int throughRoundNo) {
        return validatedSealedRounds(caseId, throughRoundNo);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.validatedSealedRounds(String,int)」。
    // 具体功能：「ActiveCourtroomContextAssembler.validatedSealedRounds(String,int)」：校验validated已封存Rounds；实际协作者为 「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「EnumSet.noneOf」、「objectMapper.createArrayNode」；不满足前置条件时抛出 「IllegalStateException」，最终返回「ArrayNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.validatedSealedRounds(String,int)」的上游调用点包括 「ActiveCourtroomContextAssembler.assembleFinalConvergence」、「ActiveCourtroomContextAssembler.sealedRounds」。
    // 下游影响：「ActiveCourtroomContextAssembler.validatedSealedRounds(String,int)」向下依次触达 「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「EnumSet.noneOf」、「objectMapper.createArrayNode」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.validatedSealedRounds(String,int)」在“validated已封存Rounds”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private ArrayNode validatedSealedRounds(String caseId, int throughRoundNo) {
        ArrayNode sealedRounds = objectMapper.createArrayNode();
        Map<Integer, HearingRoundEntity> roundsByNumber = new HashMap<>();
        for (HearingRoundEntity round :
                roundRepository.findAllByCaseIdOrderByRoundNoAsc(caseId)) {
            if (round.getRoundNo() <= throughRoundNo) {
                HearingRoundEntity duplicate =
                        roundsByNumber.put(round.getRoundNo(), round);
                if (duplicate != null) {
                    throw new IllegalStateException(
                            "duplicate hearing round "
                                    + round.getRoundNo()
                                    + " in final convergence");
                }
            }
        }
        for (int expectedRoundNo = 1;
                expectedRoundNo <= throughRoundNo;
                expectedRoundNo++) {
            HearingRoundEntity round = roundsByNumber.get(expectedRoundNo);
            if (round == null) {
                throw new IllegalStateException(
                        "hearing round "
                                + expectedRoundNo
                                + " is missing from final convergence");
            }
            boolean terminal =
                    round.getRoundStatus() == HearingRoundStatus.COMPLETED
                            || round.getRoundStatus()
                                    == HearingRoundStatus.FORCED_CLOSED;
            if (!terminal || round.getClosedAt() == null) {
                throw new IllegalStateException(
                        "hearing round "
                                + expectedRoundNo
                                + " is not sealed for final convergence");
            }
            List<HearingRoundPartySubmissionEntity> submissions =
                    submissionRepository
                            .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                    caseId, expectedRoundNo);
            Set<ActorRole> participantRoles = EnumSet.noneOf(ActorRole.class);
            submissions.forEach(
                    submission ->
                            participantRoles.add(submission.getParticipantRole()));
            if (!participantRoles.contains(ActorRole.USER)
                    || !participantRoles.contains(ActorRole.MERCHANT)) {
                throw new IllegalStateException(
                        "hearing round "
                                + expectedRoundNo
                                + " requires USER and MERCHANT submissions for final convergence");
            }
            sealedRounds.add(sealedRound(round, submissions));
        }
        return sealedRounds;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.sealedRound(HearingRoundEntity,List)」。
    // 具体功能：「ActiveCourtroomContextAssembler.sealedRound(HearingRoundEntity,List)」：构建已封存轮次；实际协作者为 「objectMapper.createObjectNode」、「round.getRoundNo」、「round.getRoundStatus」、「round.getDossierVersion」；处理的关键状态/协议值包括 「round_no」、「round_status」、「dossier_version」、「stop_reason」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.sealedRound(HearingRoundEntity,List)」的上游调用点包括 「ActiveCourtroomContextAssembler.validatedSealedRounds」。
    // 下游影响：「ActiveCourtroomContextAssembler.sealedRound(HearingRoundEntity,List)」向下依次触达 「objectMapper.createObjectNode」、「round.getRoundNo」、「round.getRoundStatus」、「round.getDossierVersion」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.sealedRound(HearingRoundEntity,List)」负责主链路中的“已封存轮次”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode sealedRound(
            HearingRoundEntity round,
            List<HearingRoundPartySubmissionEntity> roundSubmissions) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("round_no", round.getRoundNo());
        node.put("round_status", round.getRoundStatus().name());
        node.put("dossier_version", round.getDossierVersion());
        node.put(
                "stop_reason",
                round.getStopReason() == null ? "" : round.getStopReason().name());
        node.set("summary", readJson(round.getSummaryJson(), "hearing round summary"));
        ArrayNode submissions = node.putArray("party_submissions");
        roundSubmissions.stream()
                .map(this::submissionNode)
                .forEach(submissions::add);
        return node;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.submissionNode(HearingRoundPartySubmissionEntity)」。
    // 具体功能：「ActiveCourtroomContextAssembler.submissionNode(HearingRoundPartySubmissionEntity)」：构建提交节点；实际协作者为 「objectMapper.createObjectNode」、「submission.getParticipantRole」、「submission.getSubmissionSource」、「submission.getSubmissionJson」；处理的关键状态/协议值包括 「participant_role」、「submission_source」、「submission」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.submissionNode(HearingRoundPartySubmissionEntity)」只由「ActiveCourtroomContextAssembler」内部流程使用，负责封装“提交节点”这一步校验、映射或状态转换。
    // 下游影响：「ActiveCourtroomContextAssembler.submissionNode(HearingRoundPartySubmissionEntity)」向下依次触达 「objectMapper.createObjectNode」、「submission.getParticipantRole」、「submission.getSubmissionSource」、「submission.getSubmissionJson」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.submissionNode(HearingRoundPartySubmissionEntity)」负责主链路中的“提交节点”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode submissionNode(HearingRoundPartySubmissionEntity submission) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("participant_role", submission.getParticipantRole().name());
        node.put("submission_source", submission.getSubmissionSource().name());
        node.set(
                "submission",
                readJson(submission.getSubmissionJson(), "hearing round party submission"));
        return node;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.attachJuryContext(ObjectNode,String,int)」。
    // 具体功能：「ActiveCourtroomContextAssembler.attachJuryContext(ObjectNode,String,int)」：按attachJury上下文：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「a2aMessageService.findForJudge」、「objectMapper.createArrayNode」、「notes.addObject」、「message.a2aMessageId」；处理的关键状态/协议值包括 「a2a_message_id」、「round_no」、「from_agent」、「to_agent」，最终返回「void」。
    // 上游调用：「ActiveCourtroomContextAssembler.attachJuryContext(ObjectNode,String,int)」的上游调用点包括 「ActiveCourtroomContextAssembler.assemble」。
    // 下游影响：「ActiveCourtroomContextAssembler.attachJuryContext(ObjectNode,String,int)」向下依次触达 「a2aMessageService.findForJudge」、「objectMapper.createArrayNode」、「notes.addObject」、「message.a2aMessageId」。
    // 系统意义：「ActiveCourtroomContextAssembler.attachJuryContext(ObjectNode,String,int)」负责主链路中的“attachJury上下文”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void attachJuryContext(ObjectNode context, String caseId, int throughRoundNo) {
        ArrayNode notes = objectMapper.createArrayNode();
        ObjectNode latestFormalReport = null;
        List<AgentA2AMessageView> messages =
                a2aMessageService.findForJudge(caseId, throughRoundNo);
        if (messages == null) {
            messages = List.of();
        }
        for (AgentA2AMessageView message : messages) {
            ObjectNode note = notes.addObject();
            note.put("a2a_message_id", message.a2aMessageId());
            note.put("round_no", message.roundNo());
            note.put("from_agent", message.fromAgent());
            note.put("to_agent", message.toAgent());
            note.put("message_type", message.messageType());
            note.set("input_refs", readJson(message.inputRefsJson(), "A2A input refs"));
            note.set("payload", readJson(message.payloadJson(), "A2A payload"));
            note.put("visibility", message.visibility());
            if (message.agentRunId() != null && !message.agentRunId().isBlank()) {
                note.put("agent_run_id", message.agentRunId());
            }
            if ("JURY_REVIEW_REPORT".equals(message.messageType())) {
                latestFormalReport = note.deepCopy();
            }
        }
        context.set("jury_a2a_notes", notes);
        if (latestFormalReport != null) {
            context.set("jury_review_report", latestFormalReport);
        } else {
            context.remove("jury_review_report");
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.executionToolDeclarations()」。
    // 具体功能：「ActiveCourtroomContextAssembler.executionToolDeclarations()」：构建执行工具Declarations；实际协作者为 「toolRegistry.definitions」、「objectMapper.createArrayNode」、「tools.addObject」、「definition.actionType」；处理的关键状态/协议值包括 「action_type」、「tool_name」、「operation」、「display_name」，最终返回「ArrayNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.executionToolDeclarations()」的上游调用点包括 「ActiveCourtroomContextAssembler.assemble」。
    // 下游影响：「ActiveCourtroomContextAssembler.executionToolDeclarations()」向下依次触达 「toolRegistry.definitions」、「objectMapper.createArrayNode」、「tools.addObject」、「definition.actionType」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.executionToolDeclarations()」负责主链路中的“执行工具Declarations”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode executionToolDeclarations() {
        ArrayNode tools = objectMapper.createArrayNode();
        List<ToolDefinition> definitions = toolRegistry.definitions();
        if (definitions == null) {
            return tools;
        }
        for (ToolDefinition definition : definitions) {
            ObjectNode tool = tools.addObject();
            tool.put("action_type", definition.actionType());
            tool.put("tool_name", definition.toolName());
            tool.put("operation", definition.operation());
            tool.put("display_name", definition.displayName());
            tool.put("description", definition.description());
            tool.put("risk_level", definition.riskLevel().name());
            tool.put("simulated", definition.simulated());
            tool.put("requires_approved_plan", definition.requiresApprovedPlan());
        }
        return tools;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.evidenceDossierContext(EvidenceDossierEntity)」。
    // 具体功能：「ActiveCourtroomContextAssembler.evidenceDossierContext(EvidenceDossierEntity)」：构建证据卷宗上下文：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「dossier.getSummaryJson」、「dossier.getTimelineJson」、「dossier.getMatrixSummaryJson」、「objectMapper.createObjectNode」；处理的关键状态/协议值包括 「source」、「active_evidence_dossier」、「dossier_id」、「dossier_version」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.evidenceDossierContext(EvidenceDossierEntity)」的上游调用点包括 「ActiveCourtroomContextAssembler.assemble」。
    // 下游影响：「ActiveCourtroomContextAssembler.evidenceDossierContext(EvidenceDossierEntity)」向下依次触达 「dossier.getSummaryJson」、「dossier.getTimelineJson」、「dossier.getMatrixSummaryJson」、「objectMapper.createObjectNode」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.evidenceDossierContext(EvidenceDossierEntity)」负责主链路中的“证据卷宗上下文”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode evidenceDossierContext(EvidenceDossierEntity dossier) {
        JsonNode summary = readJson(dossier.getSummaryJson(), "active evidence summary");
        JsonNode timeline = readJson(dossier.getTimelineJson(), "active evidence timeline");
        JsonNode matrix = readJson(dossier.getMatrixSummaryJson(), "active evidence matrix");
        ObjectNode context = objectMapper.createObjectNode();
        context.put("source", "active_evidence_dossier");
        context.put("dossier_id", dossier.getId());
        context.put("dossier_version", dossier.getDossierVersion());
        context.put("dossier_status", dossier.getDossierStatus());
        context.set(
                "summary",
                summary.isObject() ? summary.deepCopy() : objectMapper.createObjectNode());
        context.set("evidence_items", arrayOrEmpty(summary.path("evidence_items")));
        context.set("timeline", arrayOrEmpty(timeline));
        context.set(
                "fact_evidence_matrix",
                matrix.path("fact_evidence_matrix").isArray()
                        ? matrix.path("fact_evidence_matrix").deepCopy()
                        : arrayOrEmpty(matrix));
        context.set(
                "party_evidence_summary",
                summary.path("party_evidence_summary").isObject()
                        ? summary.path("party_evidence_summary").deepCopy()
                        : objectMapper.createObjectNode());
        context.set("verified_facts", arrayOrEmpty(summary.path("verified_facts")));
        context.set("contested_facts", arrayOrEmpty(summary.path("contested_facts")));
        context.set("evidence_gaps", arrayOrEmpty(summary.path("evidence_gaps")));
        context.set("authenticity_flags", arrayOrEmpty(summary.path("authenticity_flags")));
        context.set("human_review_tasks", arrayOrEmpty(matrix.path("human_review_tasks")));
        context.set(
                "evidence_clerk_a2a_handoffs",
                arrayOrEmpty(matrix.path("internal_handoffs")));
        context.put(
                "overall_confidence_score",
                summary.path("overall_confidence_score")
                        .asInt(summary.path("confidence_score").asInt(0)));
        context.put(
                "handoff_notes",
                defaultText(
                        summary.path("handoff_notes").asText(null),
                        matrix.path("handoff_notes")
                                .asText("Active evidence dossier has no handoff notes.")));
        ObjectNode rawProjection = context.putObject("raw_projection");
        rawProjection.set("summary_json", summary.deepCopy());
        rawProjection.set("timeline_json", timeline.deepCopy());
        rawProjection.set("matrix_summary_json", matrix.deepCopy());
        return context;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.readObject(String,String)」。
    // 具体功能：「ActiveCourtroomContextAssembler.readObject(String,String)」：读取对象：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isObject」、「node.deepCopy」、「readJson」；不满足前置条件时抛出 「IllegalStateException」，最终返回「ObjectNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.readObject(String,String)」的上游调用点包括 「ActiveCourtroomContextAssembler.assemble」。
    // 下游影响：「ActiveCourtroomContextAssembler.readObject(String,String)」向下依次触达 「node.isObject」、「node.deepCopy」、「readJson」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.readObject(String,String)」统一“对象”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode readObject(String json, String label) {
        JsonNode node = readJson(json, label);
        if (!node.isObject()) {
            throw new IllegalStateException(label + " must be a JSON object");
        }
        return node.deepCopy();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.readJson(String,String)」。
    // 具体功能：「ActiveCourtroomContextAssembler.readJson(String,String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「{}」，最终返回「JsonNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.readJson(String,String)」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「ActiveCourtroomContextAssembler.submissionNode」、「ActiveCourtroomContextAssembler.attachJuryContext」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「ActiveCourtroomContextAssembler.readJson(String,String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.readJson(String,String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private JsonNode readJson(String json, String label) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid " + label, exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.arrayOrEmpty(JsonNode)」。
    // 具体功能：「ActiveCourtroomContextAssembler.arrayOrEmpty(JsonNode)」：构建数组或者为空：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isArray」、「node.deepCopy」、「objectMapper.createArrayNode」，最终返回「ArrayNode」。
    // 上游调用：「ActiveCourtroomContextAssembler.arrayOrEmpty(JsonNode)」的上游调用点包括 「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「ActiveCourtroomContextAssembler.arrayOrEmpty(JsonNode)」向下依次触达 「node.isArray」、「node.deepCopy」、「objectMapper.createArrayNode」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.arrayOrEmpty(JsonNode)」负责主链路中的“数组或者为空”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray()
                ? node.deepCopy()
                : objectMapper.createArrayNode();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「ActiveCourtroomContextAssembler.defaultText(String,String)」。
    // 具体功能：「ActiveCourtroomContextAssembler.defaultText(String,String)」：构建默认文本，最终返回「String」。
    // 上游调用：「ActiveCourtroomContextAssembler.defaultText(String,String)」的上游调用点包括 「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「ActiveCourtroomContextAssembler.defaultText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActiveCourtroomContextAssembler.defaultText(String,String)」负责主链路中的“默认文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
