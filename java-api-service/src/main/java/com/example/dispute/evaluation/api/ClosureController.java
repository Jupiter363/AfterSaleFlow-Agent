/*
 * 所属模块：结案与离线评估。
 * 文件职责：把结案能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「close」、「evaluation」、「metrics」；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.ClosureView;
import com.example.dispute.evaluation.application.EvaluationMetricsView;
import com.example.dispute.evaluation.application.EvaluationReportView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【结案与离线评估 / HTTP 接口层】类型「ClosureController」。
// 类型职责：把结案能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「ClosureController」、「close」、「evaluation」、「metrics」、「success」、「actor」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api")
public class ClosureController {

    private final CaseClosureService service;
    private final Clock clock;

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.ClosureController(CaseClosureService,Clock)」。
    // 具体功能：「ClosureController.ClosureController(CaseClosureService,Clock)」：通过构造器接收 「service」(CaseClosureService)、「clock」(Clock) 并保存为「ClosureController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ClosureController.ClosureController(CaseClosureService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ClosureController.ClosureController(CaseClosureService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ClosureController.ClosureController(CaseClosureService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ClosureController(CaseClosureService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.close(String,String,Authentication,HttpServletRequest)」。
    // 具体功能：「ClosureController.close(String,String,Authentication,HttpServletRequest)」：处理「POST /api」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.close」、「ApiResponse.success」、「correlation」、「actor」，并返回「ApiResponse<ClosureView>」。
    // 上游调用：「ClosureController.close(String,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api」HTTP 请求。
    // 下游影响：「ClosureController.close(String,String,Authentication,HttpServletRequest)」向下依次触达 「service.close」、「ApiResponse.success」、「correlation」、「actor」；计算结果以「ApiResponse<ClosureView>」交给调用方。
    // 系统意义：「ClosureController.close(String,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/disputes/{caseId}/close")
    public ApiResponse<ClosureView> close(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String traceId = correlation(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId =
                correlation(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.close(
                        caseId,
                        idempotencyKey,
                        actor(authentication),
                        traceId,
                        requestId),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.evaluation(String,Authentication,HttpServletRequest)」。
    // 具体功能：「ClosureController.evaluation(String,Authentication,HttpServletRequest)」：处理「GET /api」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.evaluation」、「success」、「actor」，并返回「ApiResponse<EvaluationReportView>」。
    // 上游调用：「ClosureController.evaluation(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api」HTTP 请求。
    // 下游影响：「ClosureController.evaluation(String,Authentication,HttpServletRequest)」向下依次触达 「service.evaluation」、「success」、「actor」；计算结果以「ApiResponse<EvaluationReportView>」交给调用方。
    // 系统意义：「ClosureController.evaluation(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/disputes/{caseId}/evaluation")
    public ApiResponse<EvaluationReportView> evaluation(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.evaluation(caseId, actor(authentication)), request);
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.metrics(Authentication,HttpServletRequest)」。
    // 具体功能：「ClosureController.metrics(Authentication,HttpServletRequest)」：处理「GET /api/reviews/evaluations/metrics」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.metrics」、「success」、「actor」，并返回「ApiResponse<EvaluationMetricsView>」。
    // 上游调用：「ClosureController.metrics(Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/reviews/evaluations/metrics」HTTP 请求。
    // 下游影响：「ClosureController.metrics(Authentication,HttpServletRequest)」向下依次触达 「service.metrics」、「success」、「actor」；计算结果以「ApiResponse<EvaluationMetricsView>」交给调用方。
    // 系统意义：「ClosureController.metrics(Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/reviews/evaluations/metrics")
    public ApiResponse<EvaluationMetricsView> metrics(
            Authentication authentication, HttpServletRequest request) {
        return success(service.metrics(actor(authentication)), request);
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.success(T,HttpServletRequest)」。
    // 具体功能：「ClosureController.success(T,HttpServletRequest)」：构建成功；实际协作者为 「ApiResponse.success」、「correlation」，最终返回「ApiResponse<T>」。
    // 上游调用：「ClosureController.success(T,HttpServletRequest)」的上游调用点包括 「ClosureController.evaluation」、「ClosureController.metrics」。
    // 下游影响：「ClosureController.success(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「correlation」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「ClosureController.success(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlation(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlation(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.actor(Authentication)」。
    // 具体功能：「ClosureController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「ClosureController.actor(Authentication)」的上游调用点包括 「ClosureController.close」、「ClosureController.evaluation」、「ClosureController.metrics」。
    // 下游影响：「ClosureController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「ClosureController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【结案与离线评估 / HTTP 接口层】「ClosureController.correlation(HttpServletRequest,String)」。
    // 具体功能：「ClosureController.correlation(HttpServletRequest,String)」：读取字符串；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「ClosureController.correlation(HttpServletRequest,String)」的上游调用点包括 「ClosureController.close」、「ClosureController.success」。
    // 下游影响：「ClosureController.correlation(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「ClosureController.correlation(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlation(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException(
                "correlation id filter did not run");
    }
}
