package com.example.dispute.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.notification.api.NotificationController;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.NotificationView;
import com.example.dispute.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
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
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotificationService service;

    @Test
    void listsReadsAndCountsTheCurrentActorsInbox() throws Exception {
        when(service.list(any())).thenReturn(List.of(view(false)));
        when(service.unreadCount(any())).thenReturn(1L);
        when(service.markRead(eq("NOTICE_1"), any())).thenReturn(view(true));

        mockMvc.perform(
                        get("/api/notifications")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("NOTICE_1"))
                .andExpect(jsonPath("$.data[0].read").value(false));

        mockMvc.perform(
                        get("/api/notifications/unread-count")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1));

        mockMvc.perform(
                        post("/api/notifications/NOTICE_1/read")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
    }

    private static NotificationView view(boolean read) {
        return new NotificationView(
                "NOTICE_1",
                "CASE_1",
                "merchant-local",
                ActorRole.MERCHANT,
                NotificationType.DISPUTE_SUMMONS,
                "争议审理传票",
                "请进入证据书记官室",
                "/disputes/CASE_1/evidence",
                read,
                Instant.parse("2026-07-03T00:00:00Z"),
                read ? Instant.parse("2026-07-03T00:01:00Z") : null);
    }
}
