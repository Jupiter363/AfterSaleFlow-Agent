/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据卷宗Revision规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「reviseAfterRoundIfNeeded」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceDossierRevisionService」。
// 类型职责：编排证据卷宗Revision规则、权限校验与事实读写；本类型显式提供 「EvidenceDossierRevisionService」、「reviseAfterRoundIfNeeded」、「partyExplanations」、「matrixObject」、「emptyEvidenceBaseline」、「emptyPartyEvidenceSummary」。
// 协作关系：主要由 「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded」、「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceDossierRevisionService {

    public static final int EVIDENCE_EXPLANATION_ROUND = 2;

    private final EvidenceDossierRepository dossierRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseEventService eventService;
    private final ObjectMapper objectMapper;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.EvidenceDossierRevisionService(EvidenceDossierRepository,CaseRoomRepository,CaseEventService,ObjectMapper)」。
    // 具体功能：「EvidenceDossierRevisionService.EvidenceDossierRevisionService(EvidenceDossierRepository,CaseRoomRepository,CaseEventService,ObjectMapper)」：通过构造器接收 「dossierRepository」(EvidenceDossierRepository)、「roomRepository」(CaseRoomRepository)、「eventService」(CaseEventService)、「objectMapper」(ObjectMapper) 并保存为「EvidenceDossierRevisionService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceDossierRevisionService.EvidenceDossierRevisionService(EvidenceDossierRepository,CaseRoomRepository,CaseEventService,ObjectMapper)」的上游创建点包括 「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable」。
    // 下游影响：「EvidenceDossierRevisionService.EvidenceDossierRevisionService(EvidenceDossierRepository,CaseRoomRepository,CaseEventService,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierRevisionService.EvidenceDossierRevisionService(EvidenceDossierRepository,CaseRoomRepository,CaseEventService,ObjectMapper)」负责主链路中的“证据卷宗Revision服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceDossierRevisionService(
            EvidenceDossierRepository dossierRepository,
            CaseRoomRepository roomRepository,
            CaseEventService eventService,
            ObjectMapper objectMapper) {
        this.dossierRepository = dossierRepository;
        this.roomRepository = roomRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded(String,int,List,String)」。
    // 具体功能：「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded(String,int,List,String)」：构建revise之后轮次IfNeeded：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「dossierRepository.save」、「EvidenceDossierEntity.frozen」、「previous.getMatrixSummaryJson」；处理的关键状态/协议值包括 「updated_after_round」、「revision_reason」、「ROUND_2_EVIDENCE_EXPLANATION_REVIEW」、「supersedes_version」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded(String,int,List,String)」的上游调用点包括 「HearingRoundService.reviseEvidenceDossierAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded(String,int,List,String)」向下依次触达 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「dossierRepository.save」、「EvidenceDossierEntity.frozen」、「previous.getMatrixSummaryJson」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded(String,int,List,String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceDossierEntity reviseAfterRoundIfNeeded(
            String caseId,
            int roundNo,
            List<HearingRoundPartySubmissionEntity> submissions,
            String actorId) {
        EvidenceDossierEntity previous =
                dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .orElseGet(() -> dossierRepository.save(emptyEvidenceBaseline(caseId, actorId)));
        if (roundNo != EVIDENCE_EXPLANATION_ROUND) {
            return previous;
        }
        JsonNode latestMatrix = readTree(previous.getMatrixSummaryJson(), "latest matrix summary");
        if (latestMatrix.path("updated_after_round").asInt(-1) == EVIDENCE_EXPLANATION_ROUND) {
            return previous;
        }

        int previousVersion = previous.getDossierVersion();
        int activeVersion = previousVersion + 1;
        ObjectNode revisedMatrix = matrixObject(latestMatrix);
        revisedMatrix.put("revision_reason", "ROUND_2_EVIDENCE_EXPLANATION_REVIEW");
        revisedMatrix.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        revisedMatrix.put("supersedes_version", previousVersion);
        revisedMatrix.put("active_version", activeVersion);
        revisedMatrix.put(
                "revision_summary",
                "证据书记官已根据第 2 轮双方证据解释更新 active 证据证明矩阵，供法官第 3 轮方案确认和裁决草案使用。");
        revisedMatrix.set("round_2_party_explanations", partyExplanations(submissions));
        if (!revisedMatrix.path("fact_evidence_matrix").isArray()) {
            revisedMatrix.set("fact_evidence_matrix", objectMapper.createArrayNode());
        }
        ArrayNode revisionHistory =
                revisedMatrix.withArrayProperty("revision_history");
        ObjectNode revision = objectMapper.createObjectNode();
        revision.put("from_version", previousVersion);
        revision.put("to_version", activeVersion);
        revision.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        revision.put("updated_by_agent", "evidence-clerk");
        revision.put("actor_id", actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId);
        revisionHistory.add(revision);

        EvidenceDossierEntity active =
                dossierRepository.save(
                        EvidenceDossierEntity.frozen(
                                "EVIDENCE_DOSSIER_" + compactUuid(),
                                caseId,
                                activeVersion,
                                actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId,
                                previous.getSummaryJson(),
                                previous.getTimelineJson(),
                                json(revisedMatrix)));
        recordRevisionEvent(caseId, previousVersion, activeVersion, actorId);
        return active;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.partyExplanations(List)」。
    // 具体功能：「EvidenceDossierRevisionService.partyExplanations(List)」：构建当事方解释；实际协作者为 「objectMapper.createArrayNode」、「objectMapper.createObjectNode」、「submission.getParticipantRole」、「submission.getSubmissionSource」；处理的关键状态/协议值包括 「party_role」、「submission_source」、「statement」，最终返回「ArrayNode」。
    // 上游调用：「EvidenceDossierRevisionService.partyExplanations(List)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRevisionService.partyExplanations(List)」向下依次触达 「objectMapper.createArrayNode」、「objectMapper.createObjectNode」、「submission.getParticipantRole」、「submission.getSubmissionSource」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.partyExplanations(List)」负责主链路中的“当事方解释”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private ArrayNode partyExplanations(List<HearingRoundPartySubmissionEntity> submissions) {
        ArrayNode explanations = objectMapper.createArrayNode();
        for (HearingRoundPartySubmissionEntity submission : submissions) {
            ObjectNode item = objectMapper.createObjectNode();
            ActorRole role = submission.getParticipantRole();
            item.put("party_role", role.name());
            item.put("submission_source", submission.getSubmissionSource().name());
            item.set("statement", readTree(submission.getSubmissionJson(), role.name() + " submission"));
            explanations.add(item);
        }
        return explanations;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.matrixObject(JsonNode)」。
    // 具体功能：「EvidenceDossierRevisionService.matrixObject(JsonNode)」：构建矩阵对象：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「matrix.isObject」、「matrix.deepCopy」、「objectMapper.createObjectNode」、「matrix.isArray」；处理的关键状态/协议值包括 「fact_evidence_matrix」，最终返回「ObjectNode」。
    // 上游调用：「EvidenceDossierRevisionService.matrixObject(JsonNode)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRevisionService.matrixObject(JsonNode)」向下依次触达 「matrix.isObject」、「matrix.deepCopy」、「objectMapper.createObjectNode」、「matrix.isArray」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.matrixObject(JsonNode)」负责主链路中的“矩阵对象”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private ObjectNode matrixObject(JsonNode matrix) {
        if (matrix != null && matrix.isObject()) {
            return matrix.deepCopy();
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        if (matrix != null && matrix.isArray()) {
            wrapper.set("fact_evidence_matrix", matrix.deepCopy());
        }
        return wrapper;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.emptyEvidenceBaseline(String,String)」。
    // 具体功能：「EvidenceDossierRevisionService.emptyEvidenceBaseline(String,String)」：构建为空证据Baseline：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「EvidenceDossierEntity.frozen」、「objectMapper.createObjectNode」、「summary.putArray」、「summary.putObject」；处理的关键状态/协议值包括 「evidence-clerk」、「evidence_count」、「evidence_items」、「party_evidence_summary」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierRevisionService.emptyEvidenceBaseline(String,String)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRevisionService.emptyEvidenceBaseline(String,String)」向下依次触达 「EvidenceDossierEntity.frozen」、「objectMapper.createObjectNode」、「summary.putArray」、「summary.putObject」；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.emptyEvidenceBaseline(String,String)」负责主链路中的“为空证据Baseline”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private EvidenceDossierEntity emptyEvidenceBaseline(String caseId, String actorId) {
        String writer = actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("evidence_count", 0);
        summary.putArray("evidence_items");
        ObjectNode partySummary = summary.putObject("party_evidence_summary");
        partySummary.set("USER", emptyPartyEvidenceSummary());
        partySummary.set("MERCHANT", emptyPartyEvidenceSummary());
        summary.putArray("verified_facts");
        summary.putArray("contested_facts");
        ArrayNode gaps = summary.putArray("evidence_gaps");
        gaps.add("USER 尚未形成有效证据材料");
        gaps.add("MERCHANT 尚未形成有效证据材料");
        summary.putArray("authenticity_flags");
        summary.put("overall_confidence_score", 0);
        summary.put(
                "handoff_notes",
                "证据室尚未形成有效证据矩阵，第二轮复核将基于双方证据解释和补证记录更新。");
        summary.put("baseline_empty", true);

        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.putArray("fact_evidence_matrix");
        matrix.set("evidence_gaps", gaps.deepCopy());
        matrix.put("handoff_notes", summary.path("handoff_notes").asText());
        matrix.put("baseline_empty", true);
        return EvidenceDossierEntity.frozen(
                "EVIDENCE_DOSSIER_" + compactUuid(),
                caseId,
                1,
                writer,
                json(summary),
                "[]",
                json(matrix));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.emptyPartyEvidenceSummary()」。
    // 具体功能：「EvidenceDossierRevisionService.emptyPartyEvidenceSummary()」：构建为空当事方证据Summary；实际协作者为 「objectMapper.createObjectNode」、「summary.putArray」；处理的关键状态/协议值包括 「strong_points」、「weak_points」、「missing_items」，最终返回「ObjectNode」。
    // 上游调用：「EvidenceDossierRevisionService.emptyPartyEvidenceSummary()」的上游调用点包括 「EvidenceDossierRevisionService.emptyEvidenceBaseline」。
    // 下游影响：「EvidenceDossierRevisionService.emptyPartyEvidenceSummary()」向下依次触达 「objectMapper.createObjectNode」、「summary.putArray」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.emptyPartyEvidenceSummary()」负责主链路中的“为空当事方证据Summary”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private ObjectNode emptyPartyEvidenceSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.putArray("strong_points");
        summary.putArray("weak_points");
        summary.putArray("missing_items");
        return summary;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.recordRevisionEvent(String,int,int,String)」。
    // 具体功能：「EvidenceDossierRevisionService.recordRevisionEvent(String,int,int,String)」：记录Revision事件：先把 Optional 空值转换为明确业务异常；实际协作者为 「roomRepository.findByCaseIdAndRoomType」、「eventService.recordLifecycleEvent」、「hearingRoom.getId」；处理的关键状态/协议值包括 「previous_version」、「active_version」、「updated_after_round」、「revision_reason」，最终返回「void」。
    // 上游调用：「EvidenceDossierRevisionService.recordRevisionEvent(String,int,int,String)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」。
    // 下游影响：「EvidenceDossierRevisionService.recordRevisionEvent(String,int,int,String)」向下依次触达 「roomRepository.findByCaseIdAndRoomType」、「eventService.recordLifecycleEvent」、「hearingRoom.getId」。
    // 系统意义：「EvidenceDossierRevisionService.recordRevisionEvent(String,int,int,String)」负责主链路中的“Revision事件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void recordRevisionEvent(
            String caseId, int previousVersion, int activeVersion, String actorId) {
        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previous_version", previousVersion);
        payload.put("active_version", activeVersion);
        payload.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        payload.put("revision_reason", "ROUND_2_EVIDENCE_EXPLANATION_REVIEW");
        payload.put(
                "summary",
                "证据书记官已根据第 2 轮证据解释更新 active 证据证明矩阵。");
        eventService.recordLifecycleEvent(
                caseId,
                hearingRoom.getId(),
                "EVIDENCE_DOSSIER_REVISED",
                payload,
                "evidence-dossier-revised:" + activeVersion + ":round-" + EVIDENCE_EXPLANATION_ROUND,
                actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.readTree(String,String)」。
    // 具体功能：「EvidenceDossierRevisionService.readTree(String,String)」：读取Tree：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「{}」，最终返回「JsonNode」。
    // 上游调用：「EvidenceDossierRevisionService.readTree(String,String)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」、「EvidenceDossierRevisionService.partyExplanations」。
    // 下游影响：「EvidenceDossierRevisionService.readTree(String,String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.readTree(String,String)」统一“Tree”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private JsonNode readTree(String json, String label) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence dossier " + label, exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.json(JsonNode)」。
    // 具体功能：「EvidenceDossierRevisionService.json(JsonNode)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceDossierRevisionService.json(JsonNode)」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」、「EvidenceDossierRevisionService.emptyEvidenceBaseline」。
    // 下游影响：「EvidenceDossierRevisionService.json(JsonNode)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.json(JsonNode)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence dossier revision", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierRevisionService.compactUuid()」。
    // 具体功能：「EvidenceDossierRevisionService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceDossierRevisionService.compactUuid()」的上游调用点包括 「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」、「EvidenceDossierRevisionService.emptyEvidenceBaseline」。
    // 下游影响：「EvidenceDossierRevisionService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierRevisionService.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
