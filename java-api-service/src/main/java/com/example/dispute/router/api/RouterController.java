/*
 * 所属模块：争议路由应用层。
 * 文件职责：把Router能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「route」；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.router.application.RouteDecisionView;
import com.example.dispute.router.application.RouterApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【争议路由应用层 / HTTP 接口层】类型「RouterController」。
// 类型职责：把Router能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「RouterController」、「route」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/route")
public class RouterController {

    private final RouterApplicationService service;
    private final Clock clock;

    // 所属模块：【争议路由应用层 / HTTP 接口层】「RouterController.RouterController(RouterApplicationService,Clock)」。
    // 具体功能：「RouterController.RouterController(RouterApplicationService,Clock)」：通过构造器接收 「service」(RouterApplicationService)、「clock」(Clock) 并保存为「RouterController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RouterController.RouterController(RouterApplicationService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「RouterController.RouterController(RouterApplicationService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RouterController.RouterController(RouterApplicationService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RouterController(RouterApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【争议路由应用层 / HTTP 接口层】「RouterController.route(String,String,Authentication,HttpServletRequest)」。
    // 具体功能：「RouterController.route(String,String,Authentication,HttpServletRequest)」：处理「POST /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.route」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」，并返回「ApiResponse<RouteDecisionView>」。
    // 上游调用：「RouterController.route(String,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /」HTTP 请求。
    // 下游影响：「RouterController.route(String,String,Authentication,HttpServletRequest)」向下依次触达 「service.route」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」；计算结果以「ApiResponse<RouteDecisionView>」交给调用方。
    // 系统意义：「RouterController.route(String,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping
    public ApiResponse<RouteDecisionView> route(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.route(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal(),
                        idempotencyKey),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【争议路由应用层 / HTTP 接口层】「RouterController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「RouterController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「RouterController.correlationId(HttpServletRequest,String)」的上游调用点包括 「RouterController.route」。
    // 下游影响：「RouterController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「RouterController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
