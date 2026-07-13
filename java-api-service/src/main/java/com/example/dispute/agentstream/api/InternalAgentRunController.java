/*
 * 所属模块：Agent 流式运行。
 * 文件职责：把内部Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「start」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.api;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【Agent 流式运行 / HTTP 接口层】类型「InternalAgentRunController」。
// 类型职责：把内部Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「InternalAgentRunController」、「start」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/internal/agent-runs")
public class InternalAgentRunController {

    private final AgentRunCoordinator coordinator;
    private final Clock clock;

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「InternalAgentRunController.InternalAgentRunController(AgentRunCoordinator,Clock)」。
    // 具体功能：「InternalAgentRunController.InternalAgentRunController(AgentRunCoordinator,Clock)」：通过构造器接收 「coordinator」(AgentRunCoordinator)、「clock」(Clock) 并保存为「InternalAgentRunController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「InternalAgentRunController.InternalAgentRunController(AgentRunCoordinator,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「InternalAgentRunController.InternalAgentRunController(AgentRunCoordinator,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「InternalAgentRunController.InternalAgentRunController(AgentRunCoordinator,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public InternalAgentRunController(AgentRunCoordinator coordinator, Clock clock) {
        this.coordinator = coordinator;
        this.clock = clock;
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「InternalAgentRunController.start(InternalAgentRunRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「InternalAgentRunController.start(InternalAgentRunRequest,Authentication,HttpServletRequest)」：处理「POST /internal/agent-runs」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「coordinator.start」、「ResponseEntity.status」、「ApiResponse.success」、「authentication.getPrincipal」，并返回「ResponseEntity<ApiResponse<AgentRunAcceptedView>>」。
    // 上游调用：「InternalAgentRunController.start(InternalAgentRunRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /internal/agent-runs」HTTP 请求。
    // 下游影响：「InternalAgentRunController.start(InternalAgentRunRequest,Authentication,HttpServletRequest)」向下依次触达 「coordinator.start」、「ResponseEntity.status」、「ApiResponse.success」、「authentication.getPrincipal」；计算结果以「ResponseEntity<ApiResponse<AgentRunAcceptedView>>」交给调用方。
    // 系统意义：「InternalAgentRunController.start(InternalAgentRunRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping
    public ResponseEntity<ApiResponse<AgentRunAcceptedView>> start(
            @Valid @RequestBody InternalAgentRunRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        AuthenticatedActor actor = (AuthenticatedActor) authentication.getPrincipal();
        AgentRunAcceptedView accepted =
                coordinator.start(
                        body.toCommand(traceId, requestId, actor.actorId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        ApiResponse.success(
                                accepted, requestId, traceId, Instant.now(clock)));
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「InternalAgentRunController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「InternalAgentRunController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「InternalAgentRunController.correlationId(HttpServletRequest,String)」的上游调用点包括 「InternalAgentRunController.start」。
    // 下游影响：「InternalAgentRunController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「InternalAgentRunController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
