package com.example.dispute.workflow.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.example.dispute.workflow.api.CaseCommandController;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = CaseCommandController.class,
        properties = "app.orchestration.command-v1-enabled=true")
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
class CaseCommandControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CaseCommandService service;

    @Test
    void acceptsOnlyClientControlledCommandFields() throws Exception {
        when(service.accept(
                        eq("CASE_CommandApi"),
                        eq("command.api.1"),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(null)))
                .thenReturn(acceptance());

        mvc.perform(
                        post("/api/disputes/CASE_CommandApi/commands")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-api")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "command.api.1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(requestBody())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.command.command_id").value("command.api.1"))
                .andExpect(
                        jsonPath("$.data.command_status")
                                .value("PENDING_ORCHESTRATION"))
                .andExpect(jsonPath("$.data.idempotent_replay").value(false))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void rejectsCallerSuppliedTenantAuthority() throws Exception {
        var body = new java.util.LinkedHashMap<>(requestBody());
        body.put("tenant_surrogate", "attacker-tenant");

        mvc.perform(
                        post("/api/disputes/CASE_CommandApi/commands")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-api")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "command.api.2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(service);
    }

    @Test
    void requiresAnAuthenticatedActor() throws Exception {
        mvc.perform(
                        post("/api/disputes/CASE_CommandApi/commands")
                                .header("Idempotency-Key", "command.api.3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(requestBody())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    private static Map<String, Object> requestBody() {
        return Map.of(
                "command_type",
                "EVIDENCE_SUBMIT",
                "room_type",
                "EVIDENCE",
                "room_epoch",
                0,
                "payload_ref",
                Map.of(
                        "schema_version",
                        "evidence-command.v1",
                        "uri",
                        "urn:command:api",
                        "sha256",
                        "a".repeat(64),
                        "size_bytes",
                        128),
                "expected_process_revision",
                0,
                "deadline_at",
                "2026-07-17T10:00:00Z");
    }

    private static CaseCommandAcceptance acceptance() {
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        return new CaseCommandAcceptance(
                new CaseCommandRef(
                        "case-command-ref.v1",
                        "command.api.1",
                        "legacy-default",
                        "CASE_CommandApi",
                        1,
                        CommandType.EVIDENCE_SUBMIT,
                        RoomType.EVIDENCE,
                        0,
                        new ActorRef(
                                "user-api",
                                ActorRole.USER,
                                List.of(
                                        "case:CASE_CommandApi:command:EVIDENCE_SUBMIT")),
                        new PayloadRef(
                                "evidence-command.v1",
                                "urn:command:api",
                                "a".repeat(64),
                                128),
                        0,
                        now,
                        now.plusSeconds(3600),
                        "00-11111111111111111111111111111111-2222222222222222-01",
                        "b".repeat(64)),
                "PENDING_ORCHESTRATION",
                now,
                false);
    }
}
