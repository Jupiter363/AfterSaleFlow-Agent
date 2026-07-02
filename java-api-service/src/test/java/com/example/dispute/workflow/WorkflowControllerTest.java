package com.example.dispute.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.example.dispute.workflow.api.WorkflowController;
import com.example.dispute.workflow.application.HearingView;
import com.example.dispute.workflow.application.PartySubmissionView;
import com.example.dispute.workflow.application.WorkflowApplicationService;
import com.example.dispute.workflow.application.WorkflowStartView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkflowController.class)
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
class WorkflowControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WorkflowApplicationService service;

    @Test
    void startsWorkflowAsynchronouslyWithIdempotencyKey() throws Exception {
        when(service.start(eq("CASE_test"), any(), eq("start-1")))
                .thenReturn(
                        new WorkflowStartView(
                                "CASE_test",
                                "CASEWORKFLOW_CASE_test",
                                "STARTED",
                                "FULL_HEARING"));

        mockMvc.perform(
                        post("/api/v1/cases/CASE_test/workflow/start")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "start-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STARTED"))
                .andExpect(
                        jsonPath("$.data.workflow_id")
                                .value("CASEWORKFLOW_CASE_test"));
    }

    @Test
    void userSubmissionSignalsEvidenceWithoutWaitingForWorkflow()
            throws Exception {
        when(service.submitPartyEvidence(
                        eq("CASE_test"),
                        eq("USER"),
                        eq("photo proof"),
                        eq(List.of("EVIDENCE_1")),
                        any(),
                        eq("submit-1")))
                .thenReturn(
                        new PartySubmissionView(
                                "SUB_1",
                                "CASE_test",
                                "USER",
                                List.of("EVIDENCE_1"),
                                "CASEWORKFLOW_CASE_test",
                                "SIGNALLED"));

        mockMvc.perform(
                        post("/api/v1/cases/CASE_test/submissions/user")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "submit-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "submission_text":"photo proof",
                                          "evidence_ids":["EVIDENCE_1"]
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signal_status").value("SIGNALLED"));
    }

    @Test
    void reviewerSignalIsExposedAndHearingCanBeQueried()
            throws Exception {
        when(service.getHearing(eq("CASE_test"), any()))
                .thenReturn(
                        new HearingView(
                                "HEARING_1",
                                "CASE_test",
                                "CASEWORKFLOW_CASE_test",
                                "WAITING_EVIDENCE",
                                "evidence_gap_request_node",
                                0,
                                null,
                                false,
                                "[]",
                                null,
                                null,
                                null));

        mockMvc.perform(
                        get("/api/v1/cases/CASE_test/hearing")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_EVIDENCE"));

        mockMvc.perform(
                        post("/api/v1/cases/CASE_test/workflow/reviewer-signal")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "decision":"CONTINUE_WITH_AVAILABLE_EVIDENCE",
                                          "reason":"evidence deadline reached"
                                        }
                                        """))
                .andExpect(status().isOk());
        verify(service)
                .submitReviewerSignal(
                        eq("CASE_test"),
                        eq("CONTINUE_WITH_AVAILABLE_EVIDENCE"),
                        eq("evidence deadline reached"),
                        any());
    }
}
