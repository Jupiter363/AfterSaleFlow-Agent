/*
 * 所属模块：后端公共边界。
 * 文件职责：承载审计Recorder在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「record」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.audit;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

// 所属模块：【后端公共边界 / 核心业务层】类型「AuditRecorder」。
// 类型职责：承载审计Recorder在当前业务模块中的规则与协作边界；本类型显式提供 「AuditRecorder」、「record」、「writeJson」、「correlationId」、「compactUuid」。
// 协作关系：主要由 「CaseClosureService.completeEvaluation」、「CaseClosureService.failEvaluation」、「CaseClosureService.prepareClosure」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AuditRecorder {

    private final AuditLogRepository repository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    // 所属模块：【后端公共边界 / 核心业务层】「AuditRecorder.AuditRecorder(AuditLogRepository,AppProperties,ObjectMapper)」。
    // 具体功能：「AuditRecorder.AuditRecorder(AuditLogRepository,AppProperties,ObjectMapper)」：通过构造器接收 「repository」(AuditLogRepository)、「properties」(AppProperties)、「objectMapper」(ObjectMapper) 并保存为「AuditRecorder」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AuditRecorder.AuditRecorder(AuditLogRepository,AppProperties,ObjectMapper)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「AuditRecorder.AuditRecorder(AuditLogRepository,AppProperties,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditRecorder.AuditRecorder(AuditLogRepository,AppProperties,ObjectMapper)」负责主链路中的“审计Recorder”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AuditRecorder(
            AuditLogRepository repository,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【后端公共边界 / 核心业务层】「AuditRecorder.record(AuthenticatedActor,String,String,String,String,Map,Map)」。
    // 具体功能：「AuditRecorder.record(AuthenticatedActor,String,String,String,String,Map,Map)」：记录审计Recorder：先把新状态写入 PostgreSQL 事实表；实际协作者为 「repository.save」、「AuditLogEntity.record」、「properties.logging」、「actor.actorId」；处理的关键状态/协议值包括 「AUDIT_」、「TRACE_INTERNAL_」、「REQ_INTERNAL_」，最终返回「void」。
    // 上游调用：「AuditRecorder.record(AuthenticatedActor,String,String,String,String,Map,Map)」的上游调用点包括 「CaseClosureService.prepareClosure」、「CaseClosureService.completeEvaluation」、「CaseClosureService.failEvaluation」、「EvidenceApplicationService.upload」。
    // 下游影响：「AuditRecorder.record(AuthenticatedActor,String,String,String,String,Map,Map)」向下依次触达 「repository.save」、「AuditLogEntity.record」、「properties.logging」、「actor.actorId」。
    // 系统意义：「AuditRecorder.record(AuthenticatedActor,String,String,String,String,Map,Map)」负责主链路中的“审计Recorder”；公共组件不得暗含具体案件裁决规则
    public void record(
            AuthenticatedActor actor,
            String action,
            String resourceType,
            String resourceId,
            String caseId,
            Map<String, ?> before,
            Map<String, ?> after) {
        if (!properties.logging().auditEnabled()) {
            return;
        }
        repository.save(
                AuditLogEntity.record(
                        "AUDIT_" + compactUuid(),
                        caseId,
                        correlationId(TraceIdFilter.MDC_TRACE_KEY, "TRACE_INTERNAL_"),
                        correlationId(TraceIdFilter.MDC_REQUEST_KEY, "REQ_INTERNAL_"),
                        actor.actorId(),
                        actor.role().name(),
                        action,
                        resourceType,
                        resourceId,
                        writeJson(before),
                        writeJson(after)));
    }

    // 所属模块：【后端公共边界 / 核心业务层】「AuditRecorder.writeJson(Object)」。
    // 具体功能：「AuditRecorder.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「AuditRecorder.writeJson(Object)」的上游调用点包括 「AuditRecorder.record」。
    // 下游影响：「AuditRecorder.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「AuditRecorder.writeJson(Object)」负责主链路中的“JSON”；公共组件不得暗含具体案件裁决规则
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize audit payload", exception);
        }
    }

    // 所属模块：【后端公共边界 / 核心业务层】「AuditRecorder.correlationId(String,String)」。
    // 具体功能：「AuditRecorder.correlationId(String,String)」：读取标识；实际协作者为 「compactUuid」，最终返回「String」。
    // 上游调用：「AuditRecorder.correlationId(String,String)」的上游调用点包括 「AuditRecorder.record」。
    // 下游影响：「AuditRecorder.correlationId(String,String)」向下依次触达 「compactUuid」；计算结果以「String」交给调用方。
    // 系统意义：「AuditRecorder.correlationId(String,String)」负责主链路中的“标识”；公共组件不得暗含具体案件裁决规则
    private static String correlationId(String mdcKey, String prefix) {
        String value = MDC.get(mdcKey);
        return value == null || value.isBlank() ? prefix + compactUuid() : value;
    }

    // 所属模块：【后端公共边界 / 核心业务层】「AuditRecorder.compactUuid()」。
    // 具体功能：「AuditRecorder.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AuditRecorder.compactUuid()」的上游调用点包括 「AuditRecorder.record」、「AuditRecorder.correlationId」。
    // 下游影响：「AuditRecorder.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AuditRecorder.compactUuid()」负责主链路中的“UUID”；公共组件不得暗含具体案件裁决规则
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
