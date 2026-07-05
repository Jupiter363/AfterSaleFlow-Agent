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
import com.example.dispute.room.api.RoomTurnMemoryController;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.CaseIntakeDossierView;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.application.RoomTurnMemoryQueryService;
import com.example.dispute.room.application.RoomTurnMemoryView;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest({RoomController.class, CaseEventController.class, RoomTurnMemoryController.class})
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
    @MockitoBean private RoomTurnMemoryQueryService turnMemoryQueryService;

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

    @Test
    void returnsLatestRoomTurnMemoryForTheIntakeScroll() throws Exception {
        when(turnMemoryQueryService.latestAgentMemory(
                        eq("CASE_test"), eq(RoomType.INTAKE), any()))
                .thenReturn(Optional.of(turnMemory()));

        mockMvc.perform(
                        get("/api/disputes/CASE_test/rooms/INTAKE/turn-memory/latest")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turn_no").value(2))
                .andExpect(jsonPath("$.data.scroll_snapshot.current_outcome").value("REFUND"))
                .andExpect(jsonPath("$.data.case_intake_dossier.quality_score").value(88))
                .andExpect(jsonPath("$.data.case_intake_dossier.ready_for_next_step").value(true))
                .andExpect(jsonPath("$.data.memory_frame.prompt_memory").value("short memory"));
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

    private static RoomTurnMemoryView turnMemory() {
        var nodeFactory = JsonNodeFactory.instance;
        return new RoomTurnMemoryView(
                "CASE_test",
                RoomType.INTAKE,
                2,
                "DISPUTE_INTAKE_OFFICER",
                "我已经更新退款诉求。",
                nodeFactory.objectNode().put("requested_outcome", "REFUND"),
                nodeFactory.objectNode().put("current_outcome", "REFUND"),
                nodeFactory.arrayNode().add(nodeFactory.objectNode().put("op", "UPSERT_CARD")),
                nodeFactory.objectNode().put("prompt_memory", "short memory"),
                new CaseIntakeDossierView(
                        "CASE_test",
                        RoomType.INTAKE,
                        2,
                        nodeFactory.objectNode().put("schema_version", "intake_case_detail.v1"),
                        88,
                        true,
                        "ACCEPTED",
                        2,
                        OffsetDateTime.parse("2026-07-05T00:00:00Z")),
                OffsetDateTime.parse("2026-07-05T00:00:00Z"));
    }
}
