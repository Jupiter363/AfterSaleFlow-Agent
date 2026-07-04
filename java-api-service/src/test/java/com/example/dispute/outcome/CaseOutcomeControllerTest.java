package com.example.dispute.outcome;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.dispute.outcome.api.CaseOutcomeController;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.outcome.application.FinalDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CaseOutcomeController.class)
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
class CaseOutcomeControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CaseOutcomeService service;

    @Test
    void returnsTheHumanConfirmedDecisionAndExecutionReceipts() throws Exception {
        when(service.get(eq("CASE_outcome"), any()))
                .thenReturn(
                        new CaseOutcomeView(
                                "CASE_outcome",
                                "签收未收到争议",
                                CaseStatus.CLOSED,
                                OffsetDateTime.parse("2026-07-03T05:20:00Z"),
                                new FinalDecisionView(
                                        "支持用户退款请求",
                                        "商家举证不足。",
                                        "审核员确认证据链完整。",
                                        "HUMAN_REVIEW",
                                        true,
                                        new ObjectMapper()
                                                .readTree(
                                                        "{\"actions\":[{\"type\":\"REFUND\"}]}")),
                                List.of()));

        mockMvc.perform(
                        get("/api/disputes/CASE_outcome/outcome")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.final_decision.conclusion")
                                .value("支持用户退款请求"))
                .andExpect(
                        jsonPath("$.data.final_decision.human_confirmed")
                                .value(true))
                .andExpect(jsonPath("$.data.actions").isArray());
    }
}
