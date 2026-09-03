/*
 * 所属模块：结案与离线评估。
 * 文件职责：验证结案，覆盖 「administratorCanCloseAndQueryEvaluationAndMetrics」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.evaluation.api.ClosureController;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.ClosureView;
import com.example.dispute.evaluation.application.EvaluationMetricsView;
import com.example.dispute.evaluation.application.EvaluationReportView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【结案与离线评估 / 自动化测试层】类型「ClosureControllerTest」。
// 类型职责：集中验证结案的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「administratorCanCloseAndQueryEvaluationAndMetrics」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(ClosureController.class)
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
class ClosureControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CaseClosureService service;

    // 所属模块：【结案与离线评估 / 自动化测试层】「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics()」。
    // 具体功能：「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics()」：复现“核对完整业务行为（场景方法「administratorCanCloseAndQueryEvaluationAndMetrics」）”场景：驱动 「service.close」、「service.evaluation」、「service.metrics」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-06-28T12:00:00Z」、「CASE_1」、「close-1」、「EVAL_1」。
    // 上游调用：「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics()」守住「结案与离线评估」的可执行规格，尤其防止 「2026-06-28T12:00:00Z」、「CASE_1」、「close-1」、「EVAL_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void administratorCanCloseAndQueryEvaluationAndMetrics() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-28T12:00:00Z");
        when(service.close(
                        eq("CASE_1"),
                        eq("close-1"),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        new ClosureView(
                                "CASE_1",
                                CaseStatus.CLOSED,
                                now,
                                "EVAL_1",
                                "COMPLETED"));
        when(service.evaluation(eq("CASE_1"), any()))
                .thenReturn(
                        new EvaluationReportView(
                                "EVAL_1",
                                "CASE_1",
                                1,
                                "COMPLETED",
                                "evaluation-model",
                                "evaluation-v1",
                                JsonNodeFactory.instance
                                        .objectNode()
                                        .put("draft_approval_rate", 1.0),
                                JsonNodeFactory.instance.arrayNode(),
                                JsonNodeFactory.instance
                                        .objectNode()
                                        .put("online_case_mutated", false),
                                12L,
                                33,
                                now,
                                now));
        when(service.metrics(any()))
                .thenReturn(new EvaluationMetricsView(4, 4, 0.75, 0.25));

        mvc.perform(
                        post("/api/disputes/CASE_1/close")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN")
                                .header("Idempotency-Key", "close-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("CLOSED"))
                .andExpect(
                        jsonPath("$.data.evaluation_status")
                                .value("COMPLETED"));
        mvc.perform(
                        get("/api/disputes/CASE_1/evaluation")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.metric_scores.draft_approval_rate")
                                .value(1.0));
        mvc.perform(
                        get("/api/reviews/evaluations/metrics")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.reviewer_modification_rate")
                                .value(0.25));
    }
}
