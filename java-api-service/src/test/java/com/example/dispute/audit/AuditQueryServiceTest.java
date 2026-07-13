/*
 * 所属模块：审计追踪。
 * 文件职责：验证审计Query，覆盖 「reviewerCanReadStructuredCaseAuditLogs」、「partyCannotReadInternalAuditTrail」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dispute.audit.application.AuditLogView;
import com.example.dispute.audit.application.AuditQueryService;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【审计追踪 / 自动化测试层】类型「AuditQueryServiceTest」。
// 类型职责：集中验证审计Query的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「reviewerCanReadStructuredCaseAuditLogs」、「partyCannotReadInternalAuditTrail」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock private AuditLogRepository auditRepository;
    @Mock private FulfillmentCaseRepository caseRepository;

    private AuditQueryService service;

    // 所属模块：【审计追踪 / 自动化测试层】「AuditQueryServiceTest.setUp()」。
    // 具体功能：「AuditQueryServiceTest.setUp()」：在每个测试场景运行前创建「newObjectMapper().findAndRegisterModules」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AuditQueryServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AuditQueryServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AuditQueryServiceTest.setUp()」守住「审计追踪」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new AuditQueryService(
                        auditRepository,
                        caseRepository,
                        new ObjectMapper().findAndRegisterModules());
    }

    // 所属模块：【审计追踪 / 自动化测试层】「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs()」。
    // 具体功能：「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs()」：复现“核对完整业务行为（场景方法「reviewerCanReadStructuredCaseAuditLogs」）”场景：驱动 「caseRepository.existsById」、「auditRepository.findAllByCaseIdOrderByCreatedAtDesc」、「service.listForCase」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「AUDIT_1」、「CASE_audit」、「TRACE_1」、「REQUEST_1」。
    // 上游调用：「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs()」守住「审计追踪」的可执行规格，尤其防止 「AUDIT_1」、「CASE_audit」、「TRACE_1」、「REQUEST_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerCanReadStructuredCaseAuditLogs() {
        AuditLogEntity entity =
                AuditLogEntity.record(
                        "AUDIT_1",
                        "CASE_audit",
                        "TRACE_1",
                        "REQUEST_1",
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "REVIEW_APPROVED",
                        "REVIEW_TASK",
                        "REVIEW_1",
                        "{}",
                        "{\"decision\":\"APPROVE\"}");
        when(caseRepository.existsById("CASE_audit")).thenReturn(true);
        when(auditRepository.findAllByCaseIdOrderByCreatedAtDesc("CASE_audit"))
                .thenReturn(List.of(entity));

        List<AuditLogView> result =
                service.listForCase(
                        "CASE_audit",
                        new AuthenticatedActor(
                                "reviewer-1", ActorRole.PLATFORM_REVIEWER));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().action()).isEqualTo("REVIEW_APPROVED");
        assertThat(result.getFirst().after().path("decision").asText())
                .isEqualTo("APPROVE");
    }

    // 所属模块：【审计追踪 / 自动化测试层】「AuditQueryServiceTest.partyCannotReadInternalAuditTrail()」。
    // 具体功能：「AuditQueryServiceTest.partyCannotReadInternalAuditTrail()」：复现“核对完整业务行为（场景方法「partyCannotReadInternalAuditTrail」）”场景：驱动 「service.listForCase」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_audit」、「user-1」。
    // 上游调用：「AuditQueryServiceTest.partyCannotReadInternalAuditTrail()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AuditQueryServiceTest.partyCannotReadInternalAuditTrail()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AuditQueryServiceTest.partyCannotReadInternalAuditTrail()」守住「审计追踪」的可执行规格，尤其防止 「CASE_audit」、「user-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyCannotReadInternalAuditTrail() {
        assertThatThrownBy(
                        () ->
                                service.listForCase(
                                        "CASE_audit",
                                        new AuthenticatedActor(
                                                "user-1", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
    }
}
