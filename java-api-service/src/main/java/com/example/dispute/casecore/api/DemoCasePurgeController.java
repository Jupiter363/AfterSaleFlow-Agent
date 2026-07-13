/*
 * 所属模块：案件核心与导入。
 * 文件职责：把演示案件案件清理能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「purge」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.DemoCasePurgeService;
import com.example.dispute.casecore.application.DemoCasePurgeView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【案件核心与导入 / HTTP 接口层】类型「DemoCasePurgeController」。
// 类型职责：把演示案件案件清理能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「DemoCasePurgeController」、「purge」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes")
public class DemoCasePurgeController {

    private final DemoCasePurgeService service;
    private final Clock clock;

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DemoCasePurgeController.DemoCasePurgeController(DemoCasePurgeService,Clock)」。
    // 具体功能：「DemoCasePurgeController.DemoCasePurgeController(DemoCasePurgeService,Clock)」：通过构造器接收 「service」(DemoCasePurgeService)、「clock」(Clock) 并保存为「DemoCasePurgeController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「DemoCasePurgeController.DemoCasePurgeController(DemoCasePurgeService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DemoCasePurgeController.DemoCasePurgeController(DemoCasePurgeService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DemoCasePurgeController.DemoCasePurgeController(DemoCasePurgeService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DemoCasePurgeController(DemoCasePurgeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DemoCasePurgeController.purge(String,Authentication,HttpServletRequest)」。
    // 具体功能：「DemoCasePurgeController.purge(String,Authentication,HttpServletRequest)」：处理「DELETE /api/disputes」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.purge」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」，并返回「ApiResponse<DemoCasePurgeView>」。
    // 上游调用：「DemoCasePurgeController.purge(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「DELETE /api/disputes」HTTP 请求。
    // 下游影响：「DemoCasePurgeController.purge(String,Authentication,HttpServletRequest)」向下依次触达 「service.purge」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」；计算结果以「ApiResponse<DemoCasePurgeView>」交给调用方。
    // 系统意义：「DemoCasePurgeController.purge(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @DeleteMapping("/{caseId}")
    public ApiResponse<DemoCasePurgeView> purge(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String requestId =
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId =
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        DemoCasePurgeView result =
                service.purge(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal());
        return ApiResponse.success(result, requestId, traceId, Instant.now(clock));
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DemoCasePurgeController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「DemoCasePurgeController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「DemoCasePurgeController.correlationId(HttpServletRequest,String)」的上游调用点包括 「DemoCasePurgeController.purge」。
    // 下游影响：「DemoCasePurgeController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「DemoCasePurgeController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
