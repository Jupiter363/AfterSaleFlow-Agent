/*
 * 所属模块：确定性补救规划。
 * 文件职责：把补救能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「get」；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.remedy.application.RemedyPlanView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【确定性补救规划 / HTTP 接口层】类型「RemedyController」。
// 类型职责：把补救能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「RemedyController」、「get」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/remedy-plan")
public class RemedyController {

    private final RemedyApplicationService service;
    private final Clock clock;

    // 所属模块：【确定性补救规划 / HTTP 接口层】「RemedyController.RemedyController(RemedyApplicationService,Clock)」。
    // 具体功能：「RemedyController.RemedyController(RemedyApplicationService,Clock)」：通过构造器接收 「service」(RemedyApplicationService)、「clock」(Clock) 并保存为「RemedyController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RemedyController.RemedyController(RemedyApplicationService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「RemedyController.RemedyController(RemedyApplicationService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyController.RemedyController(RemedyApplicationService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RemedyController(RemedyApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【确定性补救规划 / HTTP 接口层】「RemedyController.get(String,Authentication,HttpServletRequest)」。
    // 具体功能：「RemedyController.get(String,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「ApiResponse.success」、「authentication.getPrincipal」、「request.getAttribute」，并返回「ApiResponse<RemedyPlanView>」。
    // 上游调用：「RemedyController.get(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「RemedyController.get(String,Authentication,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「authentication.getPrincipal」、「request.getAttribute」；计算结果以「ApiResponse<RemedyPlanView>」交给调用方。
    // 系统意义：「RemedyController.get(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<RemedyPlanView> get(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.get(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal()),
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }
}
