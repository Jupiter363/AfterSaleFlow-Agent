package com.example.dispute.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.audit.api.AuditController;
import com.example.dispute.audit.application.AuditLogView;
import com.example.dispute.audit.application.AuditQueryService;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditController.class)
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
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AuditQueryService service;

    @Test
    void exposesCaseAuditTrailInTheUnifiedEnvelope() throws Exception {
        when(service.listForCase(eq("CASE_audit"), any()))
                .thenReturn(
                        List.of(
                                new AuditLogView(
                                        "AUDIT_1",
                                        "CASE_audit",
                                        "TRACE_1",
                                        "REQUEST_1",
                                        "reviewer-1",
                                        "PLATFORM_REVIEWER",
                                        "java-api-service",
                                        "CASE_CLOSED",
                                        "FULFILLMENT_CASE",
                                        "CASE_audit",
                                        "SUCCESS",
                                        objectMapper.readTree("{}"),
                                        objectMapper.readTree(
                                                "{\"case_status\":\"CLOSED\"}"),
                                        objectMapper.readTree("{}"),
                                        OffsetDateTime.parse(
                                                "2026-06-28T00:00:00Z"))));

        mockMvc.perform(
                        get("/api/v1/cases/CASE_audit/audit-logs")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("CASE_CLOSED"))
                .andExpect(
                        jsonPath("$.data[0].after.case_status")
                                .value("CLOSED"));
    }
}
