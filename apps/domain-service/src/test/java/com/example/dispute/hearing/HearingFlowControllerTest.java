package com.example.dispute.hearing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.hearing.api.HearingFlowController;
import com.example.dispute.hearing.application.HearingFlowRuntimeService;
import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.query.HearingProjectionQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HearingFlowController.class)
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
class HearingFlowControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private HearingFlowRuntimeService runtimeService;
    @MockitoBean private HearingProjectionQueryService projectionQueryService;
    @MockitoBean private SettlementService settlementService;

    @Test
    void getUsesOnlyTheSideEffectFreeProjectionQuery() throws Exception {
        HearingFlowView flow = flow();
        when(projectionQueryService.get(eq("CASE_test"), any())).thenReturn(flow);
        when(settlementService.list(eq("CASE_test"), any())).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/disputes/CASE_test/hearing")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.flow_schema_version")
                        .value("hearing_flow.v2"))
                .andExpect(jsonPath("$.data.status.stage_code")
                        .value("PARTY_ANSWERS_OPEN"))
                .andExpect(jsonPath("$.data.status.stage_sequence").value(5))
                .andExpect(jsonPath("$.data.status.party_statuses.USER").value("PENDING"));

        verify(projectionQueryService).get(eq("CASE_test"), any());
        verifyNoInteractions(runtimeService);
    }

    @Test
    void completeRemainsAReadOnlyProjectionGate() throws Exception {
        HearingFlowView flow = flow();
        when(projectionQueryService.completeGate(eq("CASE_test"), any())).thenReturn(flow);
        when(settlementService.list(eq("CASE_test"), any())).thenReturn(List.of());

        mockMvc.perform(
                        post("/api/disputes/CASE_test/hearing/complete")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.stage_code")
                        .value("PARTY_ANSWERS_OPEN"))
                .andExpect(jsonPath("$.data.decision_chain").isMap());

        verify(projectionQueryService).completeGate(eq("CASE_test"), any());
        verifyNoInteractions(runtimeService);
    }

    @Test
    void getMapsAnUninitializedHearingProjectionToAStableConflict() throws Exception {
        when(projectionQueryService.get(eq("CASE_test"), any()))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.CASE_STATUS_INVALID,
                                "hearing flow is not initialized",
                                Map.of("case_id", "CASE_test")));

        mockMvc.perform(
                        get("/api/disputes/CASE_test/hearing")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_STATUS_INVALID"))
                .andExpect(jsonPath("$.message").value("hearing flow is not initialized"))
                .andExpect(jsonPath("$.details.case_id").value("CASE_test"));

        verifyNoInteractions(runtimeService, settlementService);
    }

    private static HearingFlowView flow() {
        return new HearingFlowView(
                new HearingFlowView.Status(
                        "hearing_flow.v2",
                        "PARTY_ANSWERS_OPEN",
                        "PARTY_ANSWERS_OPEN",
                        5,
                        "WAITING_PARTIES",
                        "RUNNING",
                        Instant.parse("2026-07-24T00:20:00Z"),
                        Instant.parse("2026-07-24T03:00:00Z"),
                        Map.of("USER", "PENDING", "MERCHANT", "PENDING"),
                        List.of(),
                        false,
                        null),
                null,
                null,
                null,
                Map.of());
    }
}
