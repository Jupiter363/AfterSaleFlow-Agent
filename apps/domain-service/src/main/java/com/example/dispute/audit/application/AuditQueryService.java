/*
 * 所属模块：审计追踪。
 * 文件职责：编排审计Query规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「listForCase」；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【审计追踪 / 应用编排层】类型「AuditQueryService」。
// 类型职责：编排审计Query规则、权限校验与事实读写；本类型显式提供 「AuditQueryService」、「listForCase」、「toView」、「readJson」。
// 协作关系：主要由 「AuditController.list」、「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope」、「AuditQueryServiceTest.partyCannotReadInternalAuditTrail」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」 使用。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AuditQueryService {

    private static final List<ActorRole> ALLOWED_ROLES =
            List.of(
                    ActorRole.CUSTOMER_SERVICE,
                    ActorRole.PLATFORM_REVIEWER,
                    ActorRole.ADMIN,
                    ActorRole.SYSTEM);

    private final AuditLogRepository auditRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    // 所属模块：【审计追踪 / 应用编排层】「AuditQueryService.AuditQueryService(AuditLogRepository,FulfillmentCaseRepository,ObjectMapper)」。
    // 具体功能：「AuditQueryService.AuditQueryService(AuditLogRepository,FulfillmentCaseRepository,ObjectMapper)」：通过构造器接收 「auditRepository」(AuditLogRepository)、「caseRepository」(FulfillmentCaseRepository)、「objectMapper」(ObjectMapper) 并保存为「AuditQueryService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AuditQueryService.AuditQueryService(AuditLogRepository,FulfillmentCaseRepository,ObjectMapper)」的上游创建点包括 「AuditQueryServiceTest.setUp」。
    // 下游影响：「AuditQueryService.AuditQueryService(AuditLogRepository,FulfillmentCaseRepository,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditQueryService.AuditQueryService(AuditLogRepository,FulfillmentCaseRepository,ObjectMapper)」负责主链路中的“审计Query服务”；审计数据只追加不回写，普通当事人不能读取平台内部记录
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AuditQueryService(
            AuditLogRepository auditRepository,
            FulfillmentCaseRepository caseRepository,
            ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【审计追踪 / 应用编排层】「AuditQueryService.listForCase(String,AuthenticatedActor)」。
    // 具体功能：「AuditQueryService.listForCase(String,AuthenticatedActor)」：列出面向案件：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「caseRepository.existsById」、「auditRepository.findAllByCaseIdOrderByCreatedAtDesc」、「actor.role」；不满足前置条件时抛出 「ForbiddenException」、「NotFoundException」；处理的关键状态/协议值包括 「case_id」，最终返回「List<AuditLogView>」。
    // 上游调用：「AuditQueryService.listForCase(String,AuthenticatedActor)」的上游调用点包括 「AuditController.list」、「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」、「AuditQueryServiceTest.partyCannotReadInternalAuditTrail」。
    // 下游影响：「AuditQueryService.listForCase(String,AuthenticatedActor)」向下依次触达 「caseRepository.existsById」、「auditRepository.findAllByCaseIdOrderByCreatedAtDesc」、「actor.role」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AuditQueryService.listForCase(String,AuthenticatedActor)」定义原子提交边界；审计数据只追加不回写，普通当事人不能读取平台内部记录
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<AuditLogView> listForCase(
            String caseId, AuthenticatedActor actor) {
        if (!ALLOWED_ROLES.contains(actor.role())) {
            throw new ForbiddenException("actor cannot view case audit logs");
        }
        if (!caseRepository.existsById(caseId)) {
            throw new NotFoundException(
                    ErrorCode.CASE_NOT_FOUND,
                    "case not found",
                    Map.of("case_id", caseId));
        }
        return auditRepository.findAllByCaseIdOrderByCreatedAtDesc(caseId)
                .stream()
                .map(this::toView)
                .toList();
    }

    // 所属模块：【审计追踪 / 应用编排层】「AuditQueryService.toView(AuditLogEntity)」。
    // 具体功能：「AuditQueryService.toView(AuditLogEntity)」：转换视图；实际协作者为 「entity.getId」、「entity.getCaseId」、「entity.getTraceId」、「entity.getRequestId」，最终返回「AuditLogView」。
    // 上游调用：「AuditQueryService.toView(AuditLogEntity)」只由「AuditQueryService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「AuditQueryService.toView(AuditLogEntity)」向下依次触达 「entity.getId」、「entity.getCaseId」、「entity.getTraceId」、「entity.getRequestId」；计算结果以「AuditLogView」交给调用方。
    // 系统意义：「AuditQueryService.toView(AuditLogEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；审计数据只追加不回写，普通当事人不能读取平台内部记录
    private AuditLogView toView(AuditLogEntity entity) {
        return new AuditLogView(
                entity.getId(),
                entity.getCaseId(),
                entity.getTraceId(),
                entity.getRequestId(),
                entity.getUserId(),
                entity.getRole(),
                entity.getService(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getOutcome(),
                readJson(entity.getBeforeJson()),
                readJson(entity.getAfterJson()),
                readJson(entity.getMetadataJson()),
                entity.getCreatedAt());
    }

    // 所属模块：【审计追踪 / 应用编排层】「AuditQueryService.readJson(String)」。
    // 具体功能：「AuditQueryService.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「AuditQueryService.readJson(String)」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditQueryService.readJson(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「AuditQueryService.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；审计数据只追加不回写，普通当事人不能读取平台内部记录
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted audit json", exception);
        }
    }
}
