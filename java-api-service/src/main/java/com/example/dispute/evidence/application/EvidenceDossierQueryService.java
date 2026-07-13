/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排按角色裁剪的冻结卷宗查询规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「get」、「latest」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceDossierQueryService」。
// 类型职责：编排按角色裁剪的冻结卷宗查询规则、权限校验与事实读写；本类型显式提供 「EvidenceDossierQueryService」、「get」、「latest」、「view」、「assertCanAccess」、「readMap」。
// 协作关系：主要由 「EvidenceController.frozenDossier」、「EvidenceController.latestDossier」、「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」、「EvidenceDossierQueryServiceTest.setUp」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceDossierQueryService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceDossierItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.EvidenceDossierQueryService(FulfillmentCaseRepository,EvidenceDossierRepository,EvidenceDossierItemRepository,ObjectMapper)」。
    // 具体功能：「EvidenceDossierQueryService.EvidenceDossierQueryService(FulfillmentCaseRepository,EvidenceDossierRepository,EvidenceDossierItemRepository,ObjectMapper)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「dossierRepository」(EvidenceDossierRepository)、「itemRepository」(EvidenceDossierItemRepository)、「objectMapper」(ObjectMapper) 并保存为「EvidenceDossierQueryService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceDossierQueryService.EvidenceDossierQueryService(FulfillmentCaseRepository,EvidenceDossierRepository,EvidenceDossierItemRepository,ObjectMapper)」的上游创建点包括 「EvidenceDossierQueryServiceTest.setUp」。
    // 下游影响：「EvidenceDossierQueryService.EvidenceDossierQueryService(FulfillmentCaseRepository,EvidenceDossierRepository,EvidenceDossierItemRepository,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierQueryService.EvidenceDossierQueryService(FulfillmentCaseRepository,EvidenceDossierRepository,EvidenceDossierItemRepository,ObjectMapper)」负责主链路中的“证据卷宗Query服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceDossierQueryService(
            FulfillmentCaseRepository caseRepository,
            EvidenceDossierRepository dossierRepository,
            EvidenceDossierItemRepository itemRepository,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.dossierRepository = dossierRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.get(String,int,AuthenticatedActor)」。
    // 具体功能：「EvidenceDossierQueryService.get(String,int,AuthenticatedActor)」：读取冻结证据卷宗：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「dossierRepository.findByCaseIdAndDossierVersion」、「assertCanAccess」、「view」，最终返回「FrozenEvidenceDossierView」。
    // 上游调用：「EvidenceDossierQueryService.get(String,int,AuthenticatedActor)」的上游调用点包括 「EvidenceController.frozenDossier」。
    // 下游影响：「EvidenceDossierQueryService.get(String,int,AuthenticatedActor)」向下依次触达 「dossierRepository.findByCaseIdAndDossierVersion」、「assertCanAccess」、「view」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierQueryService.get(String,int,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public FrozenEvidenceDossierView get(
            String caseId, int version, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseIdAndDossierVersion(caseId, version)
                        .orElseThrow(() -> new IllegalArgumentException("dossier version not found"));
        return view(dossier);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.latest(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceDossierQueryService.latest(String,AuthenticatedActor)」：构建最新版本：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「assertCanAccess」、「view」，最终返回「FrozenEvidenceDossierView」。
    // 上游调用：「EvidenceDossierQueryService.latest(String,AuthenticatedActor)」的上游调用点包括 「EvidenceController.latestDossier」、「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」。
    // 下游影响：「EvidenceDossierQueryService.latest(String,AuthenticatedActor)」向下依次触达 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「assertCanAccess」、「view」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceDossierQueryService.latest(String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public FrozenEvidenceDossierView latest(
            String caseId, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("dossier not found"));
        return view(dossier);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.view(EvidenceDossierEntity)」。
    // 具体功能：「EvidenceDossierQueryService.view(EvidenceDossierEntity)」：构建视图；实际协作者为 「itemRepository.findAllByDossierIdOrderBySequenceNo」、「dossier.getCaseId」、「dossier.getId」、「dossier.getDossierVersion」，最终返回「FrozenEvidenceDossierView」。
    // 上游调用：「EvidenceDossierQueryService.view(EvidenceDossierEntity)」的上游调用点包括 「EvidenceDossierQueryService.get」、「EvidenceDossierQueryService.latest」。
    // 下游影响：「EvidenceDossierQueryService.view(EvidenceDossierEntity)」向下依次触达 「itemRepository.findAllByDossierIdOrderBySequenceNo」、「dossier.getCaseId」、「dossier.getId」、「dossier.getDossierVersion」；计算结果以「FrozenEvidenceDossierView」交给调用方。
    // 系统意义：「EvidenceDossierQueryService.view(EvidenceDossierEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private FrozenEvidenceDossierView view(EvidenceDossierEntity dossier) {
        return new FrozenEvidenceDossierView(
                dossier.getCaseId(),
                dossier.getId(),
                dossier.getDossierVersion(),
                dossier.getDossierStatus(),
                readMap(dossier.getSummaryJson()),
                readList(dossier.getTimelineJson()),
                readMatrix(dossier.getMatrixSummaryJson()),
                itemRepository
                        .findAllByDossierIdOrderBySequenceNo(dossier.getId())
                        .stream()
                        .map(item -> item.getEvidenceId())
                        .toList());
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.assertCanAccess(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceDossierQueryService.assertCanAccess(String,AuthenticatedActor)」：断言Can访问：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「actor.role」、「actor.actorId」、「dispute.getUserId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「EvidenceDossierQueryService.assertCanAccess(String,AuthenticatedActor)」的上游调用点包括 「EvidenceDossierQueryService.get」、「EvidenceDossierQueryService.latest」。
    // 下游影响：「EvidenceDossierQueryService.assertCanAccess(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「actor.role」、「actor.actorId」、「dispute.getUserId」。
    // 系统意义：「EvidenceDossierQueryService.assertCanAccess(String,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void assertCanAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access evidence dossier");
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.readMap(String)」。
    // 具体功能：「EvidenceDossierQueryService.readMap(String)」：读取映射；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「Map<String, Object>」。
    // 上游调用：「EvidenceDossierQueryService.readMap(String)」的上游调用点包括 「EvidenceDossierQueryService.view」。
    // 下游影响：「EvidenceDossierQueryService.readMap(String)」向下依次触达 「objectMapper.readValue」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「EvidenceDossierQueryService.readMap(String)」统一“映射”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier summary", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.readList(String)」。
    // 具体功能：「EvidenceDossierQueryService.readList(String)」：读取列表；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<Map<String, Object>>」。
    // 上游调用：「EvidenceDossierQueryService.readList(String)」的上游调用点包括 「EvidenceDossierQueryService.view」。
    // 下游影响：「EvidenceDossierQueryService.readList(String)」向下依次触达 「objectMapper.readValue」；计算结果以「List<Map<String, Object>>」交给调用方。
    // 系统意义：「EvidenceDossierQueryService.readList(String)」统一“列表”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<Map<String, Object>> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier projection", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceDossierQueryService.readMatrix(String)」。
    // 具体功能：「EvidenceDossierQueryService.readMatrix(String)」：读取矩阵：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「node.path("fact_evidence_matrix").isArray」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「fact_evidence_matrix」，最终返回「List<Map<String, Object>>」。
    // 上游调用：「EvidenceDossierQueryService.readMatrix(String)」的上游调用点包括 「EvidenceDossierQueryService.view」。
    // 下游影响：「EvidenceDossierQueryService.readMatrix(String)」向下依次触达 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「node.path("fact_evidence_matrix").isArray」；计算结果以「List<Map<String, Object>>」交给调用方。
    // 系统意义：「EvidenceDossierQueryService.readMatrix(String)」统一“矩阵”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<Map<String, Object>> readMatrix(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode matrix =
                    node.isObject() && node.path("fact_evidence_matrix").isArray()
                            ? node.path("fact_evidence_matrix")
                            : node;
            return objectMapper.convertValue(matrix, new TypeReference<>() {});
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier projection", exception);
        }
    }
}
