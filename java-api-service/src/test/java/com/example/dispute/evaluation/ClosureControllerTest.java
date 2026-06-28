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
                        post("/api/v1/cases/CASE_1/close")
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
                        get("/api/v1/cases/CASE_1/evaluation")
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
                        get("/api/v1/evaluations/metrics")
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
