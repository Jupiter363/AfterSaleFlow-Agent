package com.example.dispute.remedy;

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
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.api.RemedyController;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.remedy.application.RemedyPlanView;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RemedyController.class)
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
class RemedyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RemedyApplicationService service;

    @Test
    void returnsApprovalGatedPlanDto() throws Exception {
        when(service.get(eq("CASE_remedy"), any()))
                .thenReturn(
                        new RemedyPlanView(
                                "REMEDY_1",
                                "CASE_remedy",
                                null,
                                1,
                                RouteType.RULE_BASED_RESOLUTION,
                                "PENDING_APPROVAL",
                                RiskLevel.HIGH,
                                new BigDecimal("0.00"),
                                "CNY",
                                List.of(
                                        new PlannedRemedyAction(
                                                "REFUND",
                                                Map.of(),
                                                "REMEDY:CASE_remedy:1:0:REFUND",
                                                List.of(
                                                        "PLATFORM_REVIEW_APPROVED"),
                                                RiskLevel.HIGH,
                                                true)),
                                List.of("PLATFORM_REVIEW_APPROVED"),
                                List.of("NOTIFY_USER_AFTER_EXECUTION"),
                                true,
                                OffsetDateTime.parse(
                                        "2026-06-28T00:00:00Z")));

        mockMvc.perform(
                        get("/api/v1/cases/CASE_remedy/remedy-plan")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "user-remedy")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.actions[0].action_type").value("REFUND"))
                .andExpect(jsonPath("$.data.actions[0].requires_approval").value(true))
                .andExpect(jsonPath("$.data.requires_human_review").value(true))
                .andExpect(jsonPath("$.data.actions_json").doesNotExist());
    }
}
