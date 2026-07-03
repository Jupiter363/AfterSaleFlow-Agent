package com.example.dispute.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.room.api.CaseEventController;
import com.example.dispute.room.api.RoomController;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest({RoomController.class, CaseEventController.class})
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
class RoomAndEventControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RoomMessageService messageService;
    @MockitoBean private CaseEventService eventService;

    @Test
    void postsAnIdempotentRoomMessageAndReturnsItsSequence() throws Exception {
        when(messageService.post(
                        eq("CASE_test"),
                        eq(RoomType.EVIDENCE),
                        any(),
                        any(),
                        eq("msg-1"),
                        any()))
                .thenReturn(message());

        mockMvc.perform(
                        post("/api/disputes/CASE_test/rooms/EVIDENCE/messages")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "msg-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "message_type": "PARTY_TEXT",
                                          "text": "物流显示签收，但我没有收到。",
                                          "attachment_refs": []
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sequence_no").value(1));
    }

    @Test
    void startsAnSseSubscriptionAfterTheLastEventIdCursor() throws Exception {
        when(eventService.subscribe(eq("CASE_test"), eq(4L), any()))
                .thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(
                        get("/api/disputes/CASE_test/events")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Last-Event-ID", "4")
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    private static RoomMessageView message() {
        return new RoomMessageView(
                "MESSAGE_1",
                "CASE_test",
                "ROOM_1",
                1,
                "USER",
                "user-local",
                MessageType.PARTY_TEXT,
                "物流显示签收，但我没有收到。",
                List.of(),
                Instant.parse("2026-07-03T00:00:00Z"));
    }
}
