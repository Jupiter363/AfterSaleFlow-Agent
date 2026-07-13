/*
 * 所属模块：执行工具目录。
 * 文件职责：把内部工具能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「executionTools」；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
package com.example.dispute.tool.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【执行工具目录 / HTTP 接口层】类型「InternalToolCatalogController」。
// 类型职责：把内部工具能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「InternalToolCatalogController」、「executionTools」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/internal/tools")
public class InternalToolCatalogController {

    private final ToolRegistry toolRegistry;
    private final Clock clock;

    // 所属模块：【执行工具目录 / HTTP 接口层】「InternalToolCatalogController.InternalToolCatalogController(ToolRegistry,Clock)」。
    // 具体功能：「InternalToolCatalogController.InternalToolCatalogController(ToolRegistry,Clock)」：通过构造器接收 「toolRegistry」(ToolRegistry)、「clock」(Clock) 并保存为「InternalToolCatalogController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「InternalToolCatalogController.InternalToolCatalogController(ToolRegistry,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「InternalToolCatalogController.InternalToolCatalogController(ToolRegistry,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「InternalToolCatalogController.InternalToolCatalogController(ToolRegistry,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public InternalToolCatalogController(ToolRegistry toolRegistry, Clock clock) {
        this.toolRegistry = toolRegistry;
        this.clock = clock;
    }

    // 所属模块：【执行工具目录 / HTTP 接口层】「InternalToolCatalogController.executionTools(HttpServletRequest)」。
    // 具体功能：「InternalToolCatalogController.executionTools(HttpServletRequest)」：处理「GET /internal/tools/execution」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「toolRegistry.definitions」、「ApiResponse.success」、「request.getAttribute」，并返回「ApiResponse<List<ToolDefinition>>」。
    // 上游调用：「InternalToolCatalogController.executionTools(HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /internal/tools/execution」HTTP 请求。
    // 下游影响：「InternalToolCatalogController.executionTools(HttpServletRequest)」向下依次触达 「toolRegistry.definitions」、「ApiResponse.success」、「request.getAttribute」；计算结果以「ApiResponse<List<ToolDefinition>>」交给调用方。
    // 系统意义：「InternalToolCatalogController.executionTools(HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/execution")
    public ApiResponse<List<ToolDefinition>> executionTools(HttpServletRequest request) {
        return ApiResponse.success(
                toolRegistry.definitions(),
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }
}
