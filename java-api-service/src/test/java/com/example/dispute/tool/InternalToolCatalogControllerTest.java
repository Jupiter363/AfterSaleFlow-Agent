/*
 * 所属模块：执行工具目录。
 * 文件职责：验证内部工具，覆盖 「systemActorCanReadExecutionToolCatalog」、「nonSystemActorsCannotReadExecutionToolCatalog」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
package com.example.dispute.tool;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.tool.api.InternalToolCatalogController;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【执行工具目录 / 自动化测试层】类型「InternalToolCatalogControllerTest」。
// 类型职责：集中验证内部工具的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「systemActorCanReadExecutionToolCatalog」、「nonSystemActorsCannotReadExecutionToolCatalog」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(InternalToolCatalogController.class)
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
class InternalToolCatalogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ToolRegistry toolRegistry;

    // 所属模块：【执行工具目录 / 自动化测试层】「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog()」。
    // 具体功能：「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog()」：复现“核对完整业务行为（场景方法「systemActorCanReadExecutionToolCatalog」）”场景：驱动 「toolRegistry.definitions」、「mvc.perform」、「when」、「status」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND」、「after_sale_tool」、「refund」、「模拟退款」。
    // 上游调用：「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「InternalToolCatalogControllerTest.systemActorCanReadExecutionToolCatalog()」守住「执行工具目录」的可执行规格，尤其防止 「REFUND」、「after_sale_tool」、「refund」、「模拟退款」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void systemActorCanReadExecutionToolCatalog() throws Exception {
        when(toolRegistry.definitions())
                .thenReturn(
                        List.of(
                                new ToolDefinition(
                                        "REFUND",
                                        "after_sale_tool",
                                        "refund",
                                        "模拟退款",
                                        "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                                        RiskLevel.HIGH,
                                        true,
                                        true)));

        mvc.perform(
                        get("/internal/tools/execution")
                                .header(
                                        HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER,
                                        "python-agent-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action_type").value("REFUND"))
                .andExpect(jsonPath("$.data[0].tool_name").value("after_sale_tool"))
                .andExpect(jsonPath("$.data[0].operation").value("refund"))
                .andExpect(jsonPath("$.data[0].display_name").value("模拟退款"))
                .andExpect(jsonPath("$.data[0].risk_level").value("HIGH"))
                .andExpect(jsonPath("$.data[0].simulated").value(true))
                .andExpect(jsonPath("$.data[0].requires_approved_plan").value(true));
    }

    // 所属模块：【执行工具目录 / 自动化测试层】「InternalToolCatalogControllerTest.nonSystemActorsCannotReadExecutionToolCatalog()」。
    // 具体功能：「InternalToolCatalogControllerTest.nonSystemActorsCannotReadExecutionToolCatalog()」：复现“核对完整业务行为（场景方法「nonSystemActorsCannotReadExecutionToolCatalog」）”场景：驱动 「mvc.perform」、「status」、「andExpect」、「status().isForbidden」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「admin-1」、「ADMIN」。
    // 上游调用：「InternalToolCatalogControllerTest.nonSystemActorsCannotReadExecutionToolCatalog()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「InternalToolCatalogControllerTest.nonSystemActorsCannotReadExecutionToolCatalog()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「InternalToolCatalogControllerTest.nonSystemActorsCannotReadExecutionToolCatalog()」守住「执行工具目录」的可执行规格，尤其防止 「admin-1」、「ADMIN」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void nonSystemActorsCannotReadExecutionToolCatalog() throws Exception {
        mvc.perform(
                        get("/internal/tools/execution")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "admin-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "ADMIN"))
                .andExpect(status().isForbidden());
    }
}
