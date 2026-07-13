/*
 * 所属模块：确定性工具执行。
 * 文件职责：验证执行，覆盖 「administratorCanExecuteApprovedPlanAndListActionRecords」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.api.ExecutionController;
import com.example.dispute.executor.application.ActionRecordView;
import com.example.dispute.executor.application.ExecutionBatchView;
import com.example.dispute.executor.application.ToolExecutorService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【确定性工具执行 / 自动化测试层】类型「ExecutionControllerTest」。
// 类型职责：集中验证执行的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「administratorCanExecuteApprovedPlanAndListActionRecords」、「action」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(ExecutionController.class)
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
class ExecutionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ToolExecutorService service;

    // 所属模块：【确定性工具执行 / 自动化测试层】「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords()」。
    // 具体功能：「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords()」：复现“核对完整业务行为（场景方法「administratorCanExecuteApprovedPlanAndListActionRecords」）”场景：驱动 「service.executeApprovedActions」、「service.actions」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_1」、「command-1」、「REMEDY_1」、「APPROVAL_1」。
    // 上游调用：「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords()」守住「确定性工具执行」的可执行规格，尤其防止 「CASE_1」、「command-1」、「REMEDY_1」、「APPROVAL_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void administratorCanExecuteApprovedPlanAndListActionRecords() throws Exception {
        ActionRecordView action = action();
        when(service.executeApprovedActions(eq("CASE_1"), eq("command-1"), any()))
                .thenReturn(
                        new ExecutionBatchView(
                                "CASE_1",
                                "REMEDY_1",
                                "APPROVAL_1",
                                true,
                                List.of(action)));
        when(service.actions(eq("CASE_1"), any())).thenReturn(List.of(action));

        mvc.perform(
                        post("/api/disputes/CASE_1/execution/execute")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN")
                                .header("Idempotency-Key", "command-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all_succeeded").value(true))
                .andExpect(
                        jsonPath("$.data.actions[0].execution_status")
                                .value("SUCCEEDED"));

        mvc.perform(
                        get("/api/disputes/CASE_1/actions")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "admin-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action_type").value("REFUND"));

        verify(service)
                .executeApprovedActions(eq("CASE_1"), eq("command-1"), any());
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ExecutionControllerTest.action()」。
    // 具体功能：「ExecutionControllerTest.action()」：作为测试辅助方法为“核对完整业务行为（场景方法「action」）”组装或读取「ActionRecordView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「ExecutionControllerTest.action()」由本测试类中的 「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords」 调用。
    // 下游影响：「ExecutionControllerTest.action()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ExecutionControllerTest.action()」守住「确定性工具执行」的可执行规格，尤其防止 「2026-06-28T12:00:00Z」、「ACTION_1」、「CASE_1」、「REMEDY_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static ActionRecordView action() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-28T12:00:00Z");
        return new ActionRecordView(
                "ACTION_1",
                "CASE_1",
                "REMEDY_1",
                "APPROVAL_1",
                "REFUND",
                RiskLevel.HIGH,
                "refund-1",
                "reviewer-1",
                "admin-1",
                ExecutionStatus.SUCCEEDED,
                1,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                null,
                null,
                now,
                now);
    }
}
