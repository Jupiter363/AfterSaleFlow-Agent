/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证房间并且事件，覆盖 「postsAnIdempotentRoomMessageAndReturnsItsSequence」、「ensuresAnIdempotentEvidenceOpeningMessage」、「startsAnSseSubscriptionAfterTheLastEventIdCursor」、「returnsLatestRoomTurnMemoryForTheIntakeScroll」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
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

// 所属模块：【房间协作与权限 / 自动化测试层】类型「RoomAndEventControllerTest」。
// 类型职责：集中验证房间并且事件的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「postsAnIdempotentRoomMessageAndReturnsItsSequence」、「ensuresAnIdempotentEvidenceOpeningMessage」、「startsAnSseSubscriptionAfterTheLastEventIdCursor」、「returnsLatestRoomTurnMemoryForTheIntakeScroll」、「message」、「turnMemory」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence()」。
    // 具体功能：「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence()」：复现“核对完整业务行为（场景方法「postsAnIdempotentRoomMessageAndReturnsItsSequence」）”场景：驱动 「messageService.post」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「msg-1」、「user-local」、「USER」。
    // 上游调用：「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「msg-1」、「user-local」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage()」。
    // 具体功能：「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage()」：复现“核对完整业务行为（场景方法「ensuresAnIdempotentEvidenceOpeningMessage」）”场景：驱动 「messageService.ensureOpening」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「MESSAGE_OPENING」、「ROOM_1」、「CUSTOMER_SERVICE」。
    // 上游调用：「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.ensuresAnIdempotentEvidenceOpeningMessage()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「MESSAGE_OPENING」、「ROOM_1」、「CUSTOMER_SERVICE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensuresAnIdempotentEvidenceOpeningMessage() throws Exception {
        when(messageService.ensureOpening(
                        eq("CASE_test"),
                        eq(RoomType.EVIDENCE),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        new RoomMessageView(
                                "MESSAGE_OPENING",
                                "CASE_test",
                                "ROOM_1",
                                2,
                                "CUSTOMER_SERVICE",
                                "evidence-clerk",
                                MessageType.AGENT_MESSAGE,
                                "请先补充与案情匹配的证据材料。",
                                List.of(),
                                null,
                                null,
                                Instant.parse("2026-07-03T00:01:00Z")));

        mockMvc.perform(
                        post("/api/disputes/CASE_test/rooms/EVIDENCE/messages/opening")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "room-opening-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("MESSAGE_OPENING"))
                .andExpect(jsonPath("$.data.sender_role").value("CUSTOMER_SERVICE"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor()」。
    // 具体功能：「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor()」：复现“启动下一阶段（场景方法「startsAnSseSubscriptionAfterTheLastEventIdCursor」）”场景：驱动 「eventService.subscribe」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「user-local」、「USER」、「Last-Event-ID」。
    // 上游调用：「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「user-local」、「USER」、「Last-Event-ID」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll()」。
    // 具体功能：「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll()」：复现“返回正确投影（场景方法「returnsLatestRoomTurnMemoryForTheIntakeScroll」）”场景：驱动 「turnMemoryQueryService.latestAgentMemory」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「user-local」、「USER」、「$.data.turn_no」。
    // 上游调用：「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「user-local」、「USER」、「$.data.turn_no」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.message()」。
    // 具体功能：「RoomAndEventControllerTest.message()」：作为测试辅助方法为“核对完整业务行为（场景方法「message」）”组装或读取「RoomMessageView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RoomAndEventControllerTest.message()」由本测试类中的 「RoomAndEventControllerTest.postsAnIdempotentRoomMessageAndReturnsItsSequence」 调用。
    // 下游影响：「RoomAndEventControllerTest.message()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.message()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_1」、「CASE_test」、「ROOM_1」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                null,
                null,
                Instant.parse("2026-07-03T00:00:00Z"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomAndEventControllerTest.turnMemory()」。
    // 具体功能：「RoomAndEventControllerTest.turnMemory()」：作为测试辅助方法为“核对完整业务行为（场景方法「turnMemory」）”组装或读取「RoomTurnMemoryView」、「CaseIntakeDossierView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RoomAndEventControllerTest.turnMemory()」由本测试类中的 「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll」 调用。
    // 下游影响：「RoomAndEventControllerTest.turnMemory()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomAndEventControllerTest.turnMemory()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「DISPUTE_INTAKE_OFFICER」、「我已经更新退款诉求。」、「requested_outcome」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
