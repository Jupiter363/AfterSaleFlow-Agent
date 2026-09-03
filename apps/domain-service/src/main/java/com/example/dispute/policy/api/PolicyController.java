/*
 * 所属模块：规则检索。
 * 文件职责：把PolicyController能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「findActive」；读取适用政策规则并向路由、庭审和审核阶段提供可引用依据。
 * 关键边界：规则引用需要版本化，不能用模型生成文本替代正式规则事实
 */
package com.example.dispute.policy.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.policy.application.PolicyApplicationService;
import com.example.dispute.policy.application.PolicyRuleView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【规则检索 / HTTP 接口层】类型「PolicyController」。
// 类型职责：把PolicyController能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「PolicyController」、「findActive」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：规则引用需要版本化，不能用模型生成文本替代正式规则事实
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/reviews/policies")
public class PolicyController {

    private final PolicyApplicationService service;
    private final Clock clock;

    // 所属模块：【规则检索 / HTTP 接口层】「PolicyController.PolicyController(PolicyApplicationService,Clock)」。
    // 具体功能：「PolicyController.PolicyController(PolicyApplicationService,Clock)」：通过构造器接收 「service」(PolicyApplicationService)、「clock」(Clock) 并保存为「PolicyController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「PolicyController.PolicyController(PolicyApplicationService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「PolicyController.PolicyController(PolicyApplicationService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyController.PolicyController(PolicyApplicationService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public PolicyController(PolicyApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【规则检索 / HTTP 接口层】「PolicyController.findActive(String,HttpServletRequest)」。
    // 具体功能：「PolicyController.findActive(String,HttpServletRequest)」：处理「GET /api/reviews/policies」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.findActive」、「ApiResponse.success」、「correlationId」，并返回「ApiResponse<List<PolicyRuleView>>」。
    // 上游调用：「PolicyController.findActive(String,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/reviews/policies」HTTP 请求。
    // 下游影响：「PolicyController.findActive(String,HttpServletRequest)」向下依次触达 「service.findActive」、「ApiResponse.success」、「correlationId」；计算结果以「ApiResponse<List<PolicyRuleView>>」交给调用方。
    // 系统意义：「PolicyController.findActive(String,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<List<PolicyRuleView>> findActive(
            @RequestParam(required = false)
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}")
                    String scope,
            HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.findActive(scope),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【规则检索 / HTTP 接口层】「PolicyController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「PolicyController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「PolicyController.correlationId(HttpServletRequest,String)」的上游调用点包括 「PolicyController.findActive」。
    // 下游影响：「PolicyController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「PolicyController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
