/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审Collaboration，覆盖 「hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」、「completeEndpointReturnsTheServerBackedHearingStatus」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.api.HearingCollaborationController;
import com.example.dispute.hearing.application.HearingCourtBootstrapService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingStatusView;
import com.example.dispute.hearing.application.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingCollaborationControllerTest」。
// 类型职责：集中验证庭审Collaboration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」、「completeEndpointReturnsTheServerBackedHearingStatus」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class HearingCollaborationControllerTest {

    @Mock private HearingRoundService roundService;
    @Mock private SettlementService settlementService;
    @Mock private HearingCourtBootstrapService bootstrapService;
    @Mock private Authentication authentication;

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel()」。
    // 具体功能：「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel()」：复现“核对完整业务行为（场景方法「hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」）”场景：驱动 「roundService.list」、「roundService.status」、「settlementService.list」、「controller.hearing」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-08T01:00:00Z」、「user-local」、「REQ_HEARING」、「TRACE_HEARING」。
    // 上游调用：「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-08T01:00:00Z」、「user-local」、「REQ_HEARING」、「TRACE_HEARING」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-08T01:00:00Z"), ZoneOffset.UTC);
        HearingCollaborationController controller =
                new HearingCollaborationController(
                        roundService, settlementService, bootstrapService, clock);
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_HEARING");
        request.setAttribute(TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_HEARING");
        when(authentication.getPrincipal()).thenReturn(actor);
        when(roundService.list("CASE_BOOTSTRAP", actor)).thenReturn(List.of());
        when(roundService.status("CASE_BOOTSTRAP", actor))
                .thenReturn(
                        new HearingStatusView(
                                "CASE_BOOTSTRAP",
                                "ROUND_OPEN",
                                "本轮陈述中",
                                "请双方完成本轮陈述。",
                                false,
                                false,
                                null,
                                null,
                                1,
                                "FACT_STATEMENT",
                                "OPEN",
                                null,
                                false));
        when(settlementService.list("CASE_BOOTSTRAP", actor)).thenReturn(List.of());

        var response = controller.hearing("CASE_BOOTSTRAP", authentication, request);

        assertThat(response.data()).isInstanceOf(Map.class);
        InOrder order = inOrder(bootstrapService, roundService, settlementService);
        order.verify(bootstrapService).bootstrap("CASE_BOOTSTRAP", actor, "TRACE_HEARING");
        order.verify(roundService).list("CASE_BOOTSTRAP", actor);
        order.verify(settlementService).list("CASE_BOOTSTRAP", actor);
        order.verify(roundService).status("CASE_BOOTSTRAP", actor);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus()」。
    // 具体功能：「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus()」：复现“核对完整业务行为（场景方法「completeEndpointReturnsTheServerBackedHearingStatus」）”场景：驱动 「roundService.completeHearing」、「controller.complete」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-08T01:00:00Z」、「user-local」、「REQ_HEARING_COMPLETE」、「TRACE_HEARING_COMPLETE」。
    // 上游调用：「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-08T01:00:00Z」、「user-local」、「REQ_HEARING_COMPLETE」、「TRACE_HEARING_COMPLETE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeEndpointReturnsTheServerBackedHearingStatus() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-08T01:00:00Z"), ZoneOffset.UTC);
        HearingCollaborationController controller =
                new HearingCollaborationController(
                        roundService, settlementService, bootstrapService, clock);
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_HEARING_COMPLETE");
        request.setAttribute(TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_HEARING_COMPLETE");
        HearingStatusView ready =
                new HearingStatusView(
                        "CASE_BOOTSTRAP",
                        "DRAFT_READY",
                        "裁决草案已生成",
                        "AI 法官已生成裁决草案，可进入结果页查看草案说明。",
                        true,
                        false,
                        "DRAFT_READY_1",
                        null,
                        3,
                        "REMEDY_CONFIRMATION",
                        "FORCED_CLOSED",
                        null,
                        true);
        when(authentication.getPrincipal()).thenReturn(actor);
        when(roundService.completeHearing("CASE_BOOTSTRAP", actor)).thenReturn(ready);

        var response = controller.complete("CASE_BOOTSTRAP", authentication, request);

        assertThat(response.data()).isEqualTo(ready);
    }
}
