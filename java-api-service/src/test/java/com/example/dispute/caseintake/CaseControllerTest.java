package com.example.dispute.caseintake;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.caseintake.api.CaseController;
import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CaseView;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CaseController.class)
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
class CaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CaseApplicationService service;

    @Test
    void createCaseReturnsUnifiedEnvelopeAndAcceptedCase() throws Exception {
        when(service.create(any(), any(), eq("idem-api-1"), any(), any()))
                .thenReturn(caseView());

        mockMvc.perform(
                        post("/api/v1/cases")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "idem-api-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                java.util.Map.of(
                                                        "order_id",
                                                        "order-1",
                                                        "user_id",
                                                        "user-1",
                                                        "merchant_id",
                                                        "merchant-1",
                                                        "description",
                                                        "物流签收但未收到",
                                                        "channel",
                                                        "WEB"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("CASE_test"))
                .andExpect(jsonPath("$.data.case_status").value("INTAKE_COMPLETED"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void invalidCreateRequestUsesUnifiedValidationError() throws Exception {
        mockMvc.perform(
                        post("/api/v1/cases")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "idem-api-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void missingIdempotencyKeyUsesUnifiedValidationError() throws Exception {
        mockMvc.perform(
                        post("/api/v1/cases")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "user_id": "user-1",
                                          "merchant_id": "merchant-1",
                                          "description": "查询物流",
                                          "channel": "WEB"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void queryCaseReturnsTheDtoNotTheJpaEntity() throws Exception {
        when(service.get(eq("CASE_test"), any())).thenReturn(caseView());

        mockMvc.perform(
                        get("/api/v1/cases/CASE_test")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("CASE_test"))
                .andExpect(jsonPath("$.data.intake_result_json").doesNotExist());
    }

    private static CaseView caseView() {
        return new CaseView(
                "CASE_test",
                "order-1",
                null,
                "user-1",
                "merchant-1",
                "DISPUTE",
                "NON_RECEIPT",
                CaseStatus.INTAKE_COMPLETED,
                null,
                RiskLevel.HIGH,
                "物流签收争议",
                "物流签收但未收到",
                true,
                List.of(),
                false,
                OffsetDateTime.parse("2026-06-28T00:00:00Z"),
                OffsetDateTime.parse("2026-06-28T00:00:00Z"));
    }
}
