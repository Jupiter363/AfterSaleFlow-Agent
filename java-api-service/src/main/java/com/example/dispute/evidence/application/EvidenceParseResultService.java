/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据解析规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「apply」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceParseResultService」。
// 类型职责：编排证据解析规则、权限校验与事实读写；本类型显式提供 「EvidenceParseResultService」、「apply」、「writeJson」。
// 协作关系：主要由 「InternalEvidenceController.applyParseResult」、「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceParseResultService {

    private final EvidenceItemRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceParseResultService.EvidenceParseResultService(EvidenceItemRepository,ObjectMapper,AuditRecorder)」。
    // 具体功能：「EvidenceParseResultService.EvidenceParseResultService(EvidenceItemRepository,ObjectMapper,AuditRecorder)」：通过构造器接收 「repository」(EvidenceItemRepository)、「objectMapper」(ObjectMapper)、「auditRecorder」(AuditRecorder) 并保存为「EvidenceParseResultService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceParseResultService.EvidenceParseResultService(EvidenceItemRepository,ObjectMapper,AuditRecorder)」的上游创建点包括 「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata」。
    // 下游影响：「EvidenceParseResultService.EvidenceParseResultService(EvidenceItemRepository,ObjectMapper,AuditRecorder)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceParseResultService.EvidenceParseResultService(EvidenceItemRepository,ObjectMapper,AuditRecorder)」负责主链路中的“证据解析结果服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceParseResultService(
            EvidenceItemRepository repository,
            ObjectMapper objectMapper,
            AuditRecorder auditRecorder) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceParseResultService.apply(String,ParseResultCommand,AuthenticatedActor)」。
    // 具体功能：「EvidenceParseResultService.apply(String,ParseResultCommand,AuthenticatedActor)」：应用证据解析：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「repository.findById」、「actor.role」、「entity.getParseStatus」、「command.metadata」；不满足前置条件时抛出 「ForbiddenException」、「IllegalArgumentException」；处理的关键状态/协议值包括 「evidence_id」、「SUCCEEDED」、「FAILED」、「error_code」，最终返回「void」。
    // 上游调用：「EvidenceParseResultService.apply(String,ParseResultCommand,AuthenticatedActor)」的上游调用点包括 「InternalEvidenceController.applyParseResult」。
    // 下游影响：「EvidenceParseResultService.apply(String,ParseResultCommand,AuthenticatedActor)」向下依次触达 「repository.findById」、「actor.role」、「entity.getParseStatus」、「command.metadata」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceParseResultService.apply(String,ParseResultCommand,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void apply(
            String evidenceId,
            ParseResultCommand command,
            AuthenticatedActor actor) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException("only an internal service can update parse results");
        }
        EvidenceItemEntity entity =
                repository
                        .findById(evidenceId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.EVIDENCE_NOT_FOUND,
                                                "evidence not found",
                                                Map.of("evidence_id", evidenceId)));
        String before = entity.getParseStatus().name();
        Map<String, Object> extraction = new LinkedHashMap<>(command.metadata());
        if ("SUCCEEDED".equals(command.status())) {
            entity.applyParseSuccess(
                    command.text(), writeJson(extraction), actor.actorId());
        } else if ("FAILED".equals(command.status())) {
            extraction.put(
                    "error_code",
                    command.errorCode() == null
                            ? "PARSE_FAILED"
                            : command.errorCode());
            entity.applyParseFailure(writeJson(extraction), actor.actorId());
        } else {
            throw new IllegalArgumentException("unsupported parse result status");
        }
        auditRecorder.record(
                actor,
                "EVIDENCE_PARSED",
                "EVIDENCE_ITEM",
                evidenceId,
                entity.getCaseId(),
                Map.of("parse_status", before),
                Map.of("parse_status", entity.getParseStatus().name()));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceParseResultService.writeJson(Object)」。
    // 具体功能：「EvidenceParseResultService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceParseResultService.writeJson(Object)」的上游调用点包括 「EvidenceParseResultService.apply」。
    // 下游影响：「EvidenceParseResultService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceParseResultService.writeJson(Object)」负责主链路中的“JSON”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize extraction metadata", exception);
        }
    }
}
