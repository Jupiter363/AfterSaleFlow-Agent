package com.example.dispute.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.*;
import com.example.dispute.review.api.ReviewController;
import com.example.dispute.review.application.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@Import({CommonConfiguration.class,TraceIdFilter.class,HeaderAuthenticationFilter.class,SecurityConfiguration.class,
        SecurityFailureWriter.class,JsonAuthenticationEntryPoint.class,JsonAccessDeniedHandler.class,GlobalExceptionHandler.class})
class ReviewControllerTest {
    @Autowired MockMvc mvc; @MockitoBean ReviewApplicationService service;
    @Test void reviewerCanListAndSubmitAuditedDecision() throws Exception{
        when(service.list(any(),any())).thenReturn(List.of(new ReviewTaskView("REVIEW_1","CASE_1","REMEDY_1","PACKET_1","PENDING","URGENT","PLATFORM_REVIEWER",null,null,null)));
        when(service.decide(eq("REVIEW_1"),any(),any())).thenReturn(new ReviewDecisionView("APPROVAL_1","REVIEW_1","CASE_1","APPROVE","APPROVED","APPROVED_FOR_EXECUTION",true));
        mvc.perform(get("/api/v1/review-tasks").header(HeaderAuthenticationFilter.USER_ID_HEADER,"reviewer-1").header(HeaderAuthenticationFilter.ROLE_HEADER,"PLATFORM_REVIEWER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].priority").value("URGENT"));
        mvc.perform(post("/api/v1/review-tasks/REVIEW_1/decision").header(HeaderAuthenticationFilter.USER_ID_HEADER,"reviewer-1")
                        .header(HeaderAuthenticationFilter.ROLE_HEADER,"PLATFORM_REVIEWER").header("Idempotency-Key","decision-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"verified\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.execution_allowed").value(true));
    }
}
