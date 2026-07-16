/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证接待房间，覆盖 「confirmsAdmissionWithoutLegacyConfirmationNoteInput」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
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
import com.example.dispute.room.application.IntakeProgressService;
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

// 所属模块：【房间协作与权限 / 自动化测试层】类型「IntakeRoomControllerTest」。
// 类型职责：集中验证接待房间的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「confirmsAdmissionWithoutLegacyConfirmationNoteInput」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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
    @MockitoBean private IntakeProgressService progressService;

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」。
    // 具体功能：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」：复现“核对完整业务行为（场景方法「confirmsAdmissionWithoutLegacyConfirmationNoteInput」）”场景：驱动 「service.confirm」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「2026-07-06T02:00:00Z」、「merchant-local」、「MERCHANT」。
    // 上游调用：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「2026-07-06T02:00:00Z」、「merchant-local」、「MERCHANT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
        assertThat(command.getValue().confirmationNote()).isNull();
    }
}
