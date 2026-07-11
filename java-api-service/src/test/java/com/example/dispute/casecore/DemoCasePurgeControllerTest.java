package com.example.dispute.casecore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.casecore.api.DemoCasePurgeController;
import com.example.dispute.casecore.application.DemoCasePurgeService;
import com.example.dispute.casecore.application.DemoCasePurgeView;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoCasePurgeController.class)
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
class DemoCasePurgeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DemoCasePurgeService service;

    @Test
    void deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope() throws Exception {
        when(service.purge(org.mockito.ArgumentMatchers.eq("CASE_demo"), any()))
                .thenReturn(new DemoCasePurgeView("CASE_demo", true));

        mvc.perform(
                        delete("/api/disputes/CASE_demo")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.case_id").value("CASE_demo"))
                .andExpect(jsonPath("$.data.deleted").value(true));

        verify(service).purge(org.mockito.ArgumentMatchers.eq("CASE_demo"), any());
    }
}
