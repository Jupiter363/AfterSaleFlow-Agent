/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证案件事件，覆盖 「replayEndpointReturnsDurableCaseEventsForLedgerRebuild」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.api.CaseEventController;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.CaseEventView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「CaseEventControllerTest」。
// 类型职责：集中验证案件事件的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「replayEndpointReturnsDurableCaseEventsForLedgerRebuild」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class CaseEventControllerTest {

    @Mock private CaseEventService service;
    @Mock private Authentication authentication;

    // 所属模块：【房间协作与权限 / 自动化测试层】「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild()」。
    // 具体功能：「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild()」：复现“核对完整业务行为（场景方法「replayEndpointReturnsDurableCaseEventsForLedgerRebuild」）”场景：驱动 「service.replay」、「controller.replay」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-10T00:00:00Z」、「reviewer-local」、「REQ_EVENTS_REPLAY」、「TRACE_EVENTS_REPLAY」。
    // 上游调用：「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild()」守住「房间协作与权限」的可执行规格，尤其防止 「2026-07-10T00:00:00Z」、「reviewer-local」、「REQ_EVENTS_REPLAY」、「TRACE_EVENTS_REPLAY」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void replayEndpointReturnsDurableCaseEventsForLedgerRebuild() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
        CaseEventController controller = new CaseEventController(service, clock);
        AuthenticatedActor actor = new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_EVENTS_REPLAY");
        request.setAttribute(TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_EVENTS_REPLAY");
        CaseEventView event =
                new CaseEventView(
                        12,
                        "EXECUTION_ASSISTANT_HANDOFF",
                        null,
                        "{\"status\":\"EXECUTION_ASSISTANT_HANDOFF\"}",
                        Instant.parse("2026-07-10T00:00:00Z"));
        when(authentication.getPrincipal()).thenReturn(actor);
        when(service.replay("CASE_LEDGER", 3, actor)).thenReturn(List.of(event));

        var response = controller.replay("CASE_LEDGER", 3, authentication, request);

        assertThat(response.success()).isTrue();
        assertThat(response.requestId()).isEqualTo("REQ_EVENTS_REPLAY");
        assertThat(response.traceId()).isEqualTo("TRACE_EVENTS_REPLAY");
        assertThat(response.data()).containsExactly(event);
    }
}
