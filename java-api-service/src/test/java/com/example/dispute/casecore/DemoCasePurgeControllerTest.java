/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证演示案件案件清理，覆盖 「deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.casecore.api.DemoCasePurgeController;
import com.example.dispute.casecore.application.DemoCasePurgeService;
import com.example.dispute.casecore.application.DemoCasePurgeView;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【案件核心与导入 / 自动化测试层】类型「DemoCasePurgeControllerTest」。
// 类型职责：集中验证演示案件案件清理的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(DemoCasePurgeController.class)
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
class DemoCasePurgeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DemoCasePurgeService service;

    // 所属模块：【案件核心与导入 / 自动化测试层】「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope()」。
    // 具体功能：「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope()」：复现“核对完整业务行为（场景方法「deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope」）”场景：驱动 「service.purge」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_demo」、「reviewer-local」、「PLATFORM_REVIEWER」、「$.success」。
    // 上游调用：「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_demo」、「reviewer-local」、「PLATFORM_REVIEWER」、「$.success」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope() throws Exception {
        when(service.purge(org.mockito.ArgumentMatchers.eq("CASE_demo"), any()))
                .thenReturn(new DemoCasePurgeView("CASE_demo", true));

        mvc.perform(
                        delete("/api/disputes/CASE_demo")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.case_id").value("CASE_demo"))
                .andExpect(jsonPath("$.data.deleted").value(true));

        verify(service).purge(org.mockito.ArgumentMatchers.eq("CASE_demo"), any());
    }
}
