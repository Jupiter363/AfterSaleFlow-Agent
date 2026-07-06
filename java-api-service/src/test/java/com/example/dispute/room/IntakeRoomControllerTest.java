package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.api.IntakeRoomController;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.domain.RoomType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntakeRoomController.class)
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
class IntakeRoomControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private IntakeRoomService service;

    @Test
    void confirmsAdmissionWithoutLegacyConfirmationNoteInput() throws Exception {
        when(service.confirm(eq("CASE_test"), any(), any()))
                .thenReturn(
                        new IntakeConfirmationView(
                                "CASE_test",
                                CaseStatus.EVIDENCE_OPEN,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-06T02:00:00Z")));

        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "admissible": true,
                                          "dispute_type": "WATCH_ACCURACY",
                                          "risk_level": "MEDIUM"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("EVIDENCE_OPEN"))
                .andExpect(jsonPath("$.data.current_room").value("EVIDENCE"));

        ArgumentCaptor<IntakeConfirmationCommand> command =
                ArgumentCaptor.forClass(IntakeConfirmationCommand.class);
        verify(service).confirm(eq("CASE_test"), any(), command.capture());
        assertThat(command.getValue().confirmationNote()).isNotBlank();
    }
}
