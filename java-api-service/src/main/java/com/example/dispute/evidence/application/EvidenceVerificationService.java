/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据可信度、隔离和人工复核状态规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「verify」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceVerificationService」。
// 类型职责：编排证据可信度、隔离和人工复核状态规则、权限校验与事实读写；本类型显式提供 「EvidenceVerificationService」、「verify」、「status」、「reasons」、「checksJson」、「json」。
// 协作关系：主要由 「EvidenceController.verify」、「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」、「EvidenceVerificationAndCatalogServiceTest.setUp」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceVerificationService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.EvidenceVerificationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」。
    // 具体功能：「EvidenceVerificationService.EvidenceVerificationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「verificationRepository」(EvidenceVerificationRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「EvidenceVerificationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceVerificationService.EvidenceVerificationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」的上游创建点包括 「EvidenceVerificationAndCatalogServiceTest.setUp」。
    // 下游影响：「EvidenceVerificationService.EvidenceVerificationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceVerificationService.EvidenceVerificationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceVerificationRepository,ObjectMapper,Clock)」负责主链路中的“证据核验服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceVerificationService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.verify(String,String,EvidenceVerificationCommand,AuthenticatedActor,String)」。
    // 具体功能：「EvidenceVerificationService.verify(String,String,EvidenceVerificationCommand,AuthenticatedActor,String)」：在案件锁内确认操作者是平台核验角色、证据属于当前案件并按最新版本+1 写入核验记录；hash、签名、MIME、大小、来源和 Agent 冲突共同决定 VERIFIED/SUSPICIOUS/NEEDS_HUMAN_REVIEW，最终返回「EvidenceVerificationView」。
    // 上游调用：「EvidenceVerificationService.verify(String,String,EvidenceVerificationCommand,AuthenticatedActor,String)」的上游调用点包括 「EvidenceController.verify」、「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」。
    // 下游影响：「EvidenceVerificationService.verify(String,String,EvidenceVerificationCommand,AuthenticatedActor,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「verificationRepository.save」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceVerificationService.verify(String,String,EvidenceVerificationCommand,AuthenticatedActor,String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceVerificationView verify(
            String caseId,
            String evidenceId,
            EvidenceVerificationCommand command,
            AuthenticatedActor actor,
            String traceId) {
        if (actor.role() != ActorRole.SYSTEM
                && actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN) {
            throw new SecurityException("evidence verification requires a trusted actor");
        }
        caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        EvidenceItemEntity evidence =
                evidenceRepository.findById(evidenceId)
                        .filter(item -> item.getCaseId().equals(caseId))
                        .orElseThrow(() -> new IllegalArgumentException("evidence not found"));
        var previous =
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidence.getId());
        int version = previous.map(item -> item.getVerificationVersion() + 1).orElse(1);
        String agentFindingsJson =
                mergedAndValidatedAgentFindings(
                        caseId, command.agentFindingsJson(), previous.orElse(null));
        EvidenceVerificationStatus status = status(command);
        var reasons = reasons(command);
        EvidenceVerificationEntity saved =
                verificationRepository.save(
                        EvidenceVerificationEntity.create(
                                "VERIFY_" + compactUuid(),
                                caseId,
                                evidenceId,
                                version,
                                status,
                                checksJson(command),
                                agentFindingsJson,
                                json(reasons),
                        status == EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW
                                || status == EvidenceVerificationStatus.SUSPICIOUS,
                                clock.instant(),
                                actor.actorId(),
                                traceId));
        return view(saved);
    }

    private String mergedAndValidatedAgentFindings(
            String caseId,
            String requestedJson,
            EvidenceVerificationEntity previous) {
        ObjectNode merged = objectMapper.createObjectNode();
        if (previous != null) {
            JsonNode previousNode = parseObject(previous.getAgentFindingsJson());
            previousNode.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        JsonNode requested = parseObject(requestedJson);
        requested.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        validateFormalFactReferences(caseId, merged);
        return json(merged);
    }

    private JsonNode parseObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("agentFindingsJson must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("agentFindingsJson must be valid JSON", exception);
        }
    }

    private void validateFormalFactReferences(String caseId, JsonNode findings) {
        Set<String> allowedFactIds = officialFactIds(caseId);
        Set<String> linkedFactIds = new LinkedHashSet<>();
        JsonNode factLinks = findings.path("fact_links");
        if (factLinks.isArray()) {
            for (JsonNode link : factLinks) {
                String factId = link.path("fact_id").asText("").trim();
                if (!allowedFactIds.contains(factId)) {
                    throw new IllegalArgumentException(
                            "verification references unknown formal fact_id: " + factId);
                }
                linkedFactIds.add(factId);
            }
        }
        validateFactIdArray(findings.path("supported_fact_ids"), allowedFactIds);
        JsonNode patches = findings.path("fact_matrix_patch");
        if (patches.isArray()) {
            for (JsonNode patch : patches) {
                String factId = patch.path("fact_id").asText("").trim();
                if (!allowedFactIds.contains(factId)) {
                    throw new IllegalArgumentException(
                            "verification patch references unknown formal fact_id: " + factId);
                }
            }
        }
        if (findings.has("relevance_score")
                && findings.path("relevance_score").asDouble(0.0) >= 0.50
                && linkedFactIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "relevant evidence verification must retain a formal fact link");
        }
    }

    private void validateFactIdArray(JsonNode values, Set<String> allowedFactIds) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            String factId = value.asText("").trim();
            if (!allowedFactIds.contains(factId)) {
                throw new IllegalArgumentException(
                        "verification references unknown formal fact_id: " + factId);
            }
        }
    }

    private Set<String> officialFactIds(String caseId) {
        JsonNode dossier =
                intakeDossierRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .map(item -> parseObject(item.getDossierJson()))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "intake dossier is required for evidence verification"));
        JsonNode matrix = dossier.path("case_fact_matrix");
        if (!"case_fact_matrix.v2".equals(matrix.path("schema_version").asText())) {
            matrix = dossier.path("unilateral_case_matrix");
        }
        JsonNode rows = matrix.path("fact_rows");
        boolean supported =
                "case_fact_matrix.v2".equals(matrix.path("schema_version").asText())
                        || "unilateral_case_matrix.v1"
                                .equals(matrix.path("schema_version").asText());
        if (!supported
                || !rows.isArray()
                || rows.isEmpty()) {
            throw new IllegalStateException(
                    "frozen case_fact_matrix.v2 is required for evidence verification");
        }
        Set<String> factIds = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String factId = row.path("fact_id").asText("").trim();
            if (factId.isBlank() || !factIds.add(factId)) {
                throw new IllegalStateException("intake matrix contains invalid fact ids");
            }
        }
        return Set.copyOf(factIds);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.status(EvidenceVerificationCommand)」。
    // 具体功能：「EvidenceVerificationService.status(EvidenceVerificationCommand)」：按确定性优先级计算核验状态：基础完整性失败优先判 SUSPICIOUS，Agent 冲突或来源不明进入人工复核，全部通过才 VERIFIED，最终返回「EvidenceVerificationStatus」。
    // 上游调用：「EvidenceVerificationService.status(EvidenceVerificationCommand)」的上游调用点包括 「EvidenceVerificationService.verify」。
    // 下游影响：「EvidenceVerificationService.status(EvidenceVerificationCommand)」向下依次触达 「command.signatureValid」、「command.mimeValid」、「command.sizeAllowed」、「command.agentFlagsConflict」；计算结果以「EvidenceVerificationStatus」交给调用方。
    // 系统意义：「EvidenceVerificationService.status(EvidenceVerificationCommand)」负责主链路中的“状态”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static EvidenceVerificationStatus status(EvidenceVerificationCommand command) {
        if (!command.signatureValid() || !command.mimeValid() || !command.sizeAllowed()) {
            return EvidenceVerificationStatus.REJECTED;
        }
        if (command.agentFlagsConflict()) {
            return EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW;
        }
        if (!command.hashValid() || command.duplicate()) {
            return EvidenceVerificationStatus.SUSPICIOUS;
        }
        return command.sourceTrusted()
                ? EvidenceVerificationStatus.VERIFIED
                : EvidenceVerificationStatus.PLAUSIBLE;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.reasons(EvidenceVerificationCommand)」。
    // 具体功能：「EvidenceVerificationService.reasons(EvidenceVerificationCommand)」：把每项失败检查转换为稳定原因码列表，供审计、卷宗风险标记和前端解释使用，最终返回「java.util.List<String>」。
    // 上游调用：「EvidenceVerificationService.reasons(EvidenceVerificationCommand)」的上游调用点包括 「EvidenceVerificationService.verify」。
    // 下游影响：「EvidenceVerificationService.reasons(EvidenceVerificationCommand)」向下依次触达 「command.hashValid」、「command.signatureValid」、「command.mimeValid」、「command.sizeAllowed」；计算结果以「java.util.List<String>」交给调用方。
    // 系统意义：「EvidenceVerificationService.reasons(EvidenceVerificationCommand)」负责主链路中的“原因列表”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static java.util.List<String> reasons(EvidenceVerificationCommand command) {
        var reasons = new ArrayList<String>();
        if (!command.hashValid()) reasons.add("HASH_MISMATCH");
        if (!command.signatureValid()) reasons.add("SIGNATURE_INVALID");
        if (!command.mimeValid()) reasons.add("MIME_MISMATCH");
        if (!command.sizeAllowed()) reasons.add("FILE_SIZE_INVALID");
        if (!command.sourceTrusted()) reasons.add("SOURCE_NOT_PROVEN");
        if (command.duplicate()) reasons.add("DUPLICATE_EVIDENCE");
        if (command.agentFlagsConflict()) reasons.add("AGENT_CONSISTENCY_CONFLICT");
        if ((!command.hashValid() || command.duplicate())
                && command.signatureValid()
                && command.mimeValid()
                && command.sizeAllowed()) {
            reasons.add("SUSPECTED_FORGERY");
        }
        return reasons;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.checksJson(EvidenceVerificationCommand)」。
    // 具体功能：「EvidenceVerificationService.checksJson(EvidenceVerificationCommand)」：检查checksJSON；实际协作者为 「command.hashValid」、「command.signatureValid」、「command.sourceTrusted」、「command.mimeValid」；处理的关键状态/协议值包括 「hash_valid」、「signature_valid」、「source_trusted」、「mime_valid」，最终返回「String」。
    // 上游调用：「EvidenceVerificationService.checksJson(EvidenceVerificationCommand)」的上游调用点包括 「EvidenceVerificationService.verify」。
    // 下游影响：「EvidenceVerificationService.checksJson(EvidenceVerificationCommand)」向下依次触达 「command.hashValid」、「command.signatureValid」、「command.sourceTrusted」、「command.mimeValid」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationService.checksJson(EvidenceVerificationCommand)」在“checksJSON”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String checksJson(EvidenceVerificationCommand command) {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("hash_valid", command.hashValid());
        checks.put("signature_valid", command.signatureValid());
        checks.put("source_trusted", command.sourceTrusted());
        checks.put("mime_valid", command.mimeValid());
        checks.put("size_allowed", command.sizeAllowed());
        checks.put("duplicate", command.duplicate());
        return json(checks);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.json(Object)」。
    // 具体功能：「EvidenceVerificationService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceVerificationService.json(Object)」的上游调用点包括 「EvidenceVerificationService.verify」、「EvidenceVerificationService.checksJson」。
    // 下游影响：「EvidenceVerificationService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize verification", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.view(EvidenceVerificationEntity)」。
    // 具体功能：「EvidenceVerificationService.view(EvidenceVerificationEntity)」：构建视图；实际协作者为 「entity.getVerificationStatus」、「entity.getId」、「entity.getEvidenceId」、「entity.getVerificationVersion」，最终返回「EvidenceVerificationView」。
    // 上游调用：「EvidenceVerificationService.view(EvidenceVerificationEntity)」的上游调用点包括 「EvidenceVerificationService.verify」。
    // 下游影响：「EvidenceVerificationService.view(EvidenceVerificationEntity)」向下依次触达 「entity.getVerificationStatus」、「entity.getId」、「entity.getEvidenceId」、「entity.getVerificationVersion」；计算结果以「EvidenceVerificationView」交给调用方。
    // 系统意义：「EvidenceVerificationService.view(EvidenceVerificationEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static EvidenceVerificationView view(EvidenceVerificationEntity entity) {
        boolean include =
                entity.getVerificationStatus() != EvidenceVerificationStatus.REJECTED;
        return new EvidenceVerificationView(
                entity.getId(),
                entity.getEvidenceId(),
                entity.getVerificationVersion(),
                entity.getVerificationStatus(),
                entity.isRequiresHumanReview(),
                include,
                true,
                entity.getVerifiedAt());
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceVerificationService.compactUuid()」。
    // 具体功能：「EvidenceVerificationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceVerificationService.compactUuid()」的上游调用点包括 「EvidenceVerificationService.verify」。
    // 下游影响：「EvidenceVerificationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationService.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
