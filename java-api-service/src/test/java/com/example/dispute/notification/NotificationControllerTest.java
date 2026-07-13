/*
 * 所属模块：案件生命周期通知。
 * 文件职责：验证通知，覆盖 「listsReadsAndCountsTheCurrentActorsInbox」、「allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

// 所属模块：【案件生命周期通知 / 自动化测试层】类型「NotificationControllerTest」。
// 类型职责：集中验证通知的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「listsReadsAndCountsTheCurrentActorsInbox」、「allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor」、「view」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox()」。
    // 具体功能：「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox()」：复现“核对完整业务行为（场景方法「listsReadsAndCountsTheCurrentActorsInbox」）”场景：驱动 「service.list」、「service.unreadCount」、「service.markRead」、「service.markAllRead」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_1」、「merchant-local」、「MERCHANT」、「$.data[0].id」。
    // 上游调用：「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「merchant-local」、「MERCHANT」、「$.data[0].id」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void listsReadsAndCountsTheCurrentActorsInbox() throws Exception {
        when(service.list(any())).thenReturn(List.of(view(false)));
        when(service.unreadCount(any())).thenReturn(1L);
        when(service.markRead(eq("NOTICE_1"), any())).thenReturn(view(true));
        when(service.markAllRead(any())).thenReturn(2L);

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

        mockMvc.perform(
                        post("/api/notifications/read-all")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marked_count").value(2));
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationControllerTest.allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor()」。
    // 具体功能：「NotificationControllerTest.allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor()」：复现“允许满足条件的操作（场景方法「allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor」）”场景：驱动 「mockMvc.perform」、「delete」、「status」、「jsonPath」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「USER」、「merchant-local」、「MERCHANT」。
    // 上游调用：「NotificationControllerTest.allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationControllerTest.allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationControllerTest.allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor()」守住「案件生命周期通知」的可执行规格，尤其防止 「user-local」、「USER」、「merchant-local」、「MERCHANT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void allowsEveryInboxRoleToDismissOnlyThroughItsAuthenticatedActor() throws Exception {
        String[][] actors = {
            {"user-local", "USER"},
            {"merchant-local", "MERCHANT"},
            {"reviewer-local", "PLATFORM_REVIEWER"}
        };

        for (String[] actor : actors) {
            mockMvc.perform(
                            delete("/api/notifications/NOTICE_1")
                                    .header(
                                            HeaderAuthenticationFilter.USER_ID_HEADER,
                                            actor[0])
                                    .header(
                                            HeaderAuthenticationFilter.ROLE_HEADER,
                                            actor[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.notification_id").value("NOTICE_1"))
                    .andExpect(jsonPath("$.data.deleted").value(true));
        }

        verify(service, org.mockito.Mockito.times(3))
                .dismiss(eq("NOTICE_1"), any());
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationControllerTest.view(boolean)」。
    // 具体功能：「NotificationControllerTest.view(boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「view」）”组装或读取「NotificationView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「NotificationControllerTest.view(boolean)」由本测试类中的 「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox」 调用。
    // 下游影响：「NotificationControllerTest.view(boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「NotificationControllerTest.view(boolean)」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「CASE_1」、「merchant-local」、「争议审理传票」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
