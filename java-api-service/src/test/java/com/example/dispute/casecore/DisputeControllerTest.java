package com.example.dispute.casecore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.casecore.api.DisputeController;
import com.example.dispute.casecore.api.InternalDisputeImportController;
import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.casecore.domain.CaseSourceType;
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
import com.example.dispute.room.api.IntakeRoomController;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.domain.RoomType;
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

@WebMvcTest({
    DisputeController.class,
    InternalDisputeImportController.class,
    IntakeRoomController.class
})
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
    @MockitoBean private DisputeImportService importService;
    @MockitoBean private IntakeRoomService intakeRoomService;

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
                .andExpect(jsonPath("$.data.items[0].id").value("CASE_test"))
                .andExpect(jsonPath("$.data.items[0].source_type").value("INTAKE_CREATED"))
                .andExpect(jsonPath("$.data.items[0].current_room").value("INTAKE"))
                .andExpect(jsonPath("$.data.items[0].deadline_at").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].pending_action").value("COMPLETE_INTAKE"));
    }

    @Test
    void importsExternalDisputesThroughTheInternalServiceBoundary() throws Exception {
        when(importService.importDispute(any(), any(), eq("import-ext-1001")))
                .thenReturn(
                        new ImportedDisputeView(
                                "CASE_imported",
                                "EXTERNAL_IMPORT",
                                "OMS",
                                "EXT-1001",
                                CaseStatus.INTAKE_PENDING,
                                "INTAKE",
                                null));
        mockMvc.perform(
                        post("/internal/disputes/import")
                                .header("X-Service-Identity", "external-dispute-adapter")
                                .header("Idempotency-Key", "import-ext-1001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "source_system": "OMS",
                                          "external_case_reference": "EXT-1001",
                                          "order_reference": "ORDER-1001",
                                          "after_sales_reference": "AFTER-1001",
                                          "logistics_reference": "LOG-1001",
                                          "user_id": "user-local",
                                          "merchant_id": "merchant-local",
                                          "initiator_role": "USER",
                                          "dispute_type": "SIGNED_NOT_RECEIVED",
                                          "title": "签收未收到",
                                          "description": "用户表示未收到已签收包裹",
                                          "risk_level": "HIGH"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source_type").value("EXTERNAL_IMPORT"))
                .andExpect(jsonPath("$.data.external_case_reference").value("EXT-1001"));
    }

    @Test
    void confirmsTheIntakeDecisionThroughTheRoomBasedApi() throws Exception {
        when(intakeRoomService.confirm(eq("CASE_test"), any(), any()))
                .thenReturn(
                        new IntakeConfirmationView(
                                "CASE_test",
                                CaseStatus.EVIDENCE_OPEN,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-03T02:00:00Z")));
        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "admissible": true,
                                          "dispute_type": "SIGNED_NOT_RECEIVED",
                                          "risk_level": "HIGH",
                                          "confirmation_note": "确认信息无误，同意发起争议审理"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("EVIDENCE_OPEN"))
                .andExpect(jsonPath("$.data.current_room").value("EVIDENCE"))
                .andExpect(jsonPath("$.data.deadline_at").isNotEmpty());
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
                CaseSourceType.INTAKE_CREATED,
                null,
                null,
                "INTAKE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "COMPLETE_INTAKE",
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                null);
    }
}
