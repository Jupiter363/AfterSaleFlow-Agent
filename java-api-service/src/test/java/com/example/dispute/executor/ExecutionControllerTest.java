package com.example.dispute.executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.api.ExecutionController;
import com.example.dispute.executor.application.ActionRecordView;
import com.example.dispute.executor.application.ExecutionBatchView;
import com.example.dispute.executor.application.ToolExecutorService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExecutionController.class)
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
class ExecutionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ToolExecutorService service;

    @Test
    void administratorCanExecuteApprovedPlanAndListActionRecords() throws Exception {
        ActionRecordView action = action();
        when(service.executeApprovedActions(eq("CASE_1"), eq("command-1"), any()))
                .thenReturn(
                        new ExecutionBatchView(
                                "CASE_1",
                                "REMEDY_1",
                                "APPROVAL_1",
                                true,
                                List.of(action)));
        when(service.actions(eq("CASE_1"), any())).thenReturn(List.of(action));

        mvc.perform(
                        post("/api/disputes/CASE_1/execution/execute")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN")
                                .header("Idempotency-Key", "command-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all_succeeded").value(true))
                .andExpect(
                        jsonPath("$.data.actions[0].execution_status")
                                .value("SUCCEEDED"));

        mvc.perform(
                        get("/api/disputes/CASE_1/actions")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action_type").value("REFUND"));

        verify(service)
                .executeApprovedActions(eq("CASE_1"), eq("command-1"), any());
    }

    private static ActionRecordView action() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-28T12:00:00Z");
        return new ActionRecordView(
                "ACTION_1",
                "CASE_1",
                "REMEDY_1",
                "APPROVAL_1",
                "REFUND",
                RiskLevel.HIGH,
                "refund-1",
                "reviewer-1",
                "admin-1",
                ExecutionStatus.SUCCEEDED,
                1,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                null,
                null,
                now,
                now);
    }
}
