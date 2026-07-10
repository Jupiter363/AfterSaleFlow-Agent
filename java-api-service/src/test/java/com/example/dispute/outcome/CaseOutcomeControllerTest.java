package com.example.dispute.outcome;

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
import com.example.dispute.outcome.api.CaseOutcomeController;
import com.example.dispute.outcome.application.AdjudicationDraftView;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.outcome.application.FinalDecisionView;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
        ObjectMapper objectMapper = new ObjectMapper();
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
                                        objectMapper.readTree(
                                                "{\"actions\":[{\"type\":\"REFUND\"}]}")),
                                new AdjudicationDraftView(
                                        "DRAFT_1",
                                        2,
                                        "支持用户退款请求",
                                        new BigDecimal("0.9200"),
                                        "AI 法官形成非最终裁决草案。",
                                        "READY",
                                        objectMapper.readTree(
                                                "[{\"fact\":\"物流记录显示已签收\"}]"),
                                        objectMapper.readTree(
                                                "[{\"assessment\":\"商家证据不足以证明用户本人签收\"}]"),
                                        objectMapper.readTree(
                                                "[{\"rule\":\"签收争议举证责任\"}]"),
                                        objectMapper.readTree("[\"核验签收人身份\"]"),
                                        objectMapper.readTree(
                                                "{\"id\":\"PLAN_1\",\"actions\":[{\"action_type\":\"REFUND\",\"amount\":199}]}")),
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
                .andExpect(jsonPath("$.data.adjudication_draft.id").value("DRAFT_1"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.fact_findings[0].fact")
                                .value("物流记录显示已签收"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.evidence_assessment[0].assessment")
                                .value("商家证据不足以证明用户本人签收"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.policy_application[0].rule")
                                .value("签收争议举证责任"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.reviewer_attention[0]")
                                .value("核验签收人身份"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.approved_plan.id")
                                .value("PLAN_1"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.approved_plan.actions[0].action_type")
                                .value("REFUND"))
                .andExpect(jsonPath("$.data.actions").isArray());
    }

    @Test
    void reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint() throws Exception {
        when(service.confirmDraft(
                        eq("CASE_outcome"),
                        eq("confirmed by reviewer"),
                        eq("outcome-confirm-1"),
                        any()))
                .thenReturn(
                        new ReviewDecisionView(
                                "APPROVAL_1",
                                "REVIEW_1",
                                "CASE_outcome",
                                "APPROVE",
                                "APPROVED",
                                "APPROVED_FOR_EXECUTION",
                                true));

        mockMvc.perform(
                        post("/api/disputes/CASE_outcome/outcome/review/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .header("Idempotency-Key", "outcome-confirm-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"confirmed by reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("REVIEW_1"))
                .andExpect(jsonPath("$.data.decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.execution_allowed").value(true));
    }

    @Test
    void reviewerModifiesTheOutcomeDraftThroughCaseEndpoint() throws Exception {
        when(service.modifyDraft(
                        eq("CASE_outcome"),
                        eq("refund amount adjusted"),
                        any(),
                        eq("outcome-modify-1"),
                        any()))
                .thenReturn(
                        new ReviewDecisionView(
                                "APPROVAL_2",
                                "REVIEW_2",
                                "CASE_outcome",
                                "MODIFY_AND_APPROVE",
                                "APPROVED",
                                "APPROVED_FOR_EXECUTION",
                                true));

        mockMvc.perform(
                        post("/api/disputes/CASE_outcome/outcome/review/modify")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .header("Idempotency-Key", "outcome-modify-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "refund amount adjusted",
                                          "approved_plan": {
                                            "id": "PLAN_2",
                                            "actions": [
                                              {
                                                "action_type": "REFUND",
                                                "amount": 199
                                              }
                                            ]
                                          }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("REVIEW_2"))
                .andExpect(jsonPath("$.data.decision").value("MODIFY_AND_APPROVE"))
                .andExpect(jsonPath("$.data.execution_allowed").value(true));
    }
}
