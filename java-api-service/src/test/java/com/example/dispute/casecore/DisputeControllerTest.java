package com.example.dispute.casecore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.casecore.api.DisputeController;
import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CasePageView;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DisputeController.class)
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
class DisputeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CaseApplicationService service;

    @Test
    void createsDisputeThroughTheUnversionedFinalApi() throws Exception {
        when(service.create(any(), any(), eq("idem-dispute-1"), any(), any()))
                .thenReturn(disputeView());

        mockMvc.perform(
                        post("/api/disputes")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "idem-dispute-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                java.util.Map.of(
                                                        "initiator_role",
                                                        "USER",
                                                        "order_reference",
                                                        "order-1",
                                                        "user_id",
                                                        "user-1",
                                                        "merchant_id",
                                                        "merchant-1",
                                                        "description",
                                                        "物流显示签收，但本人没有收到货。",
                                                        "attachment_ids",
                                                        List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("CASE_test"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void readsAndListsDisputesThroughFinalPaths() throws Exception {
        when(service.get(eq("CASE_test"), any())).thenReturn(disputeView());
        when(service.list(eq(null), eq(null), eq(0), eq(20), any()))
                .thenReturn(new CasePageView(List.of(disputeView()), 0, 20, 1, 1));

        mockMvc.perform(
                        get("/api/disputes/CASE_test")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("CASE_test"));

        mockMvc.perform(
                        get("/api/disputes")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("CASE_test"));
    }

    private static CaseView disputeView() {
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
                "签收未收到争端",
                "物流显示签收，但本人没有收到货。",
                true,
                List.of(),
                false,
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                null);
    }
}
