/*
 * 所属模块：确定性工具执行。
 * 文件职责：把执行能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「execute」、「actions」；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.executor.application.ActionRecordView;
import com.example.dispute.executor.application.ExecutionBatchView;
import com.example.dispute.executor.application.ToolExecutorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【确定性工具执行 / HTTP 接口层】类型「ExecutionController」。
// 类型职责：把执行能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「ExecutionController」、「execute」、「actions」、「success」、「actor」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}")
public class ExecutionController {

    private final ToolExecutorService service;
    private final Clock clock;

    // 所属模块：【确定性工具执行 / HTTP 接口层】「ExecutionController.ExecutionController(ToolExecutorService,Clock)」。
    // 具体功能：「ExecutionController.ExecutionController(ToolExecutorService,Clock)」：通过构造器接收 「service」(ToolExecutorService)、「clock」(Clock) 并保存为「ExecutionController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ExecutionController.ExecutionController(ToolExecutorService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ExecutionController.ExecutionController(ToolExecutorService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExecutionController.ExecutionController(ToolExecutorService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ExecutionController(ToolExecutorService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【确定性工具执行 / HTTP 接口层】「ExecutionController.execute(String,String,Authentication,HttpServletRequest)」。
    // 具体功能：「ExecutionController.execute(String,String,Authentication,HttpServletRequest)」：处理「POST /execution/execute」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.executeApprovedActions」、「success」、「actor」，并返回「ApiResponse<ExecutionBatchView>」。
    // 上游调用：「ExecutionController.execute(String,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /execution/execute」HTTP 请求。
    // 下游影响：「ExecutionController.execute(String,String,Authentication,HttpServletRequest)」向下依次触达 「service.executeApprovedActions」、「success」、「actor」；计算结果以「ApiResponse<ExecutionBatchView>」交给调用方。
    // 系统意义：「ExecutionController.execute(String,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/execution/execute")
    public ApiResponse<ExecutionBatchView> execute(
            @PathVariable @NotBlank String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.executeApprovedActions(
                        caseId, idempotencyKey, actor(authentication)),
                request);
    }

    // 所属模块：【确定性工具执行 / HTTP 接口层】「ExecutionController.actions(String,Authentication,HttpServletRequest)」。
    // 具体功能：「ExecutionController.actions(String,Authentication,HttpServletRequest)」：处理「GET /actions」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.actions」、「success」、「actor」，并返回「ApiResponse<List<ActionRecordView>>」。
    // 上游调用：「ExecutionController.actions(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /actions」HTTP 请求。
    // 下游影响：「ExecutionController.actions(String,Authentication,HttpServletRequest)」向下依次触达 「service.actions」、「success」、「actor」；计算结果以「ApiResponse<List<ActionRecordView>>」交给调用方。
    // 系统意义：「ExecutionController.actions(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/actions")
    public ApiResponse<List<ActionRecordView>> actions(
            @PathVariable @NotBlank String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(service.actions(caseId, actor(authentication)), request);
    }

    // 所属模块：【确定性工具执行 / HTTP 接口层】「ExecutionController.success(T,HttpServletRequest)」。
    // 具体功能：「ExecutionController.success(T,HttpServletRequest)」：构建成功；实际协作者为 「ApiResponse.success」、「request.getAttribute」，最终返回「ApiResponse<T>」。
    // 上游调用：「ExecutionController.success(T,HttpServletRequest)」的上游调用点包括 「ExecutionController.execute」、「ExecutionController.actions」。
    // 下游影响：「ExecutionController.success(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「request.getAttribute」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「ExecutionController.success(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【确定性工具执行 / HTTP 接口层】「ExecutionController.actor(Authentication)」。
    // 具体功能：「ExecutionController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「ExecutionController.actor(Authentication)」的上游调用点包括 「ExecutionController.execute」、「ExecutionController.actions」。
    // 下游影响：「ExecutionController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「ExecutionController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }
}
