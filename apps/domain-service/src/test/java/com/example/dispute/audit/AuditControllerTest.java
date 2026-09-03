/*
 * 所属模块：审计追踪。
 * 文件职责：验证审计，覆盖 「exposesCaseAuditTrailInTheUnifiedEnvelope」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.audit.api.AuditController;
import com.example.dispute.audit.application.AuditLogView;
import com.example.dispute.audit.application.AuditQueryService;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【审计追踪 / 自动化测试层】类型「AuditControllerTest」。
// 类型职责：集中验证审计的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「exposesCaseAuditTrailInTheUnifiedEnvelope」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(AuditController.class)
@Import({
    CommonConfiguration.class,
    TraceIdFilter.class,
    HeaderAuthenticationFilter.class,
    SecurityConfiguration.class,
    SecurityFailureWriter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AuditQueryService service;

    // 所属模块：【审计追踪 / 自动化测试层】「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope()」。
    // 具体功能：「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope()」：复现“核对完整业务行为（场景方法「exposesCaseAuditTrailInTheUnifiedEnvelope」）”场景：驱动 「service.listForCase」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_audit」、「AUDIT_1」、「TRACE_1」、「REQUEST_1」。
    // 上游调用：「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AuditControllerTest.exposesCaseAuditTrailInTheUnifiedEnvelope()」守住「审计追踪」的可执行规格，尤其防止 「CASE_audit」、「AUDIT_1」、「TRACE_1」、「REQUEST_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void exposesCaseAuditTrailInTheUnifiedEnvelope() throws Exception {
        when(service.listForCase(eq("CASE_audit"), any()))
                .thenReturn(
                        List.of(
                                new AuditLogView(
                                        "AUDIT_1",
                                        "CASE_audit",
                                        "TRACE_1",
                                        "REQUEST_1",
                                        "reviewer-1",
                                        "PLATFORM_REVIEWER",
                                        "java-api-service",
                                        "CASE_CLOSED",
                                        "FULFILLMENT_CASE",
                                        "CASE_audit",
                                        "SUCCESS",
                                        objectMapper.readTree("{}"),
                                        objectMapper.readTree(
                                                "{\"case_status\":\"CLOSED\"}"),
                                        objectMapper.readTree("{}"),
                                        OffsetDateTime.parse(
                                                "2026-06-28T00:00:00Z"))));

        mockMvc.perform(
                        get("/api/disputes/CASE_audit/audit-logs")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("CASE_CLOSED"))
                .andExpect(
                        jsonPath("$.data[0].after.case_status")
                                .value("CLOSED"));
    }
}
