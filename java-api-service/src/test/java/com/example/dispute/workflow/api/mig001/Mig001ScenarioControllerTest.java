package com.example.dispute.workflow.api.mig001;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("mig001-driver")
@WebMvcTest(
        value = Mig001ScenarioController.class,
        properties = "app.orchestration.mig001-driver-enabled=true")
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
class Mig001ScenarioControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private Mig001ScenarioService service;

    @Test
    void createsOnlyForSystemIdentity() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        when(service.create(eq(token), any(), any(), any())).thenReturn(view(token));

        mvc.perform(post("/internal/orchestration/mig001/scenarios")
                        .header(HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER, "mig001-driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_id\":\"" + token + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scenario_id").value(token));

        mvc.perform(post("/internal/orchestration/mig001/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_id\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/internal/orchestration/mig001/scenarios")
                        .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                        .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_id\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsReadableOrMalformedScenarioIdentifiers() throws Exception {
        mvc.perform(post("/internal/orchestration/mig001/scenarios")
                        .header(HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER, "mig001-driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_id\":\"customer-order-123\"}"))
                .andExpect(status().isBadRequest());
    }

    private static Mig001ScenarioView view(String token) {
        return new Mig001ScenarioView(token, "CASE_MIG001", "MIG001_SYNTHETIC", "mig001-" + token,
                "epoch-1", "legacy-default", "ROOM_MIG001", "EVIDENCE", 0, 0, 0, 1,
                "SHADOW", "ACTIVE", "PENDING", "SHADOW", "PREPARING", "case-workflow-1",
                "room-workflow-1", "bootstrap-1", "PENDING", null, null);
    }
}
