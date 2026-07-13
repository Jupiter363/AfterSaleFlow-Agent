/*
 * 所属模块：审计追踪。
 * 文件职责：把审计能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「list」；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit.api;

import com.example.dispute.audit.application.AuditLogView;
import com.example.dispute.audit.application.AuditQueryService;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【审计追踪 / HTTP 接口层】类型「AuditController」。
// 类型职责：把审计能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「AuditController」、「list」、「actor」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/audit-logs")
public class AuditController {

    private final AuditQueryService service;
    private final Clock clock;

    // 所属模块：【审计追踪 / HTTP 接口层】「AuditController.AuditController(AuditQueryService,Clock)」。
    // 具体功能：「AuditController.AuditController(AuditQueryService,Clock)」：通过构造器接收 「service」(AuditQueryService)、「clock」(Clock) 并保存为「AuditController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AuditController.AuditController(AuditQueryService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「AuditController.AuditController(AuditQueryService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditController.AuditController(AuditQueryService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AuditController(AuditQueryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【审计追踪 / HTTP 接口层】「AuditController.list(String,Authentication,HttpServletRequest)」。
    // 具体功能：「AuditController.list(String,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.listForCase」、「ApiResponse.success」、「request.getAttribute」、「actor」，并返回「ApiResponse<List<AuditLogView>>」。
    // 上游调用：「AuditController.list(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「AuditController.list(String,Authentication,HttpServletRequest)」向下依次触达 「service.listForCase」、「ApiResponse.success」、「request.getAttribute」、「actor」；计算结果以「ApiResponse<List<AuditLogView>>」交给调用方。
    // 系统意义：「AuditController.list(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<List<AuditLogView>> list(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.listForCase(caseId, actor(authentication)),
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【审计追踪 / HTTP 接口层】「AuditController.actor(Authentication)」。
    // 具体功能：「AuditController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「AuditController.actor(Authentication)」的上游调用点包括 「AuditController.list」。
    // 下游影响：「AuditController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「AuditController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }
}
