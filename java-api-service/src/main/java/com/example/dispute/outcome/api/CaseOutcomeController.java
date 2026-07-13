/*
 * 所属模块：裁决结果查询。
 * 文件职责：把案件结果能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「get」、「confirmDraft」、「modifyDraft」；聚合人工终审、非最终草案、补救执行和案件时间线形成角色可见结果页。
 * 关键边界：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
 */
package com.example.dispute.outcome.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【裁决结果查询 / HTTP 接口层】类型「CaseOutcomeController」。
// 类型职责：把案件结果能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「CaseOutcomeController」、「get」、「confirmDraft」、「modifyDraft」、「actor」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/outcome")
public class CaseOutcomeController {

    private final CaseOutcomeService service;
    private final Clock clock;

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.CaseOutcomeController(CaseOutcomeService,Clock)」。
    // 具体功能：「CaseOutcomeController.CaseOutcomeController(CaseOutcomeService,Clock)」：通过构造器接收 「service」(CaseOutcomeService)、「clock」(Clock) 并保存为「CaseOutcomeController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseOutcomeController.CaseOutcomeController(CaseOutcomeService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「CaseOutcomeController.CaseOutcomeController(CaseOutcomeService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseOutcomeController.CaseOutcomeController(CaseOutcomeService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseOutcomeController(CaseOutcomeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.get(String,Authentication,HttpServletRequest)」。
    // 具体功能：「CaseOutcomeController.get(String,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「ApiResponse.success」、「actor」、「correlationId」，并返回「ApiResponse<CaseOutcomeView>」。
    // 上游调用：「CaseOutcomeController.get(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「CaseOutcomeController.get(String,Authentication,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「actor」、「correlationId」；计算结果以「ApiResponse<CaseOutcomeView>」交给调用方。
    // 系统意义：「CaseOutcomeController.get(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<CaseOutcomeView> get(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.get(caseId, actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.confirmDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「CaseOutcomeController.confirmDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」：处理「POST /review/confirm」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.confirmDraft」、「ApiResponse.success」、「body.reason」、「actor」，并返回「ApiResponse<ReviewDecisionView>」。
    // 上游调用：「CaseOutcomeController.confirmDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /review/confirm」HTTP 请求。
    // 下游影响：「CaseOutcomeController.confirmDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」向下依次触达 「service.confirmDraft」、「ApiResponse.success」、「body.reason」、「actor」；计算结果以「ApiResponse<ReviewDecisionView>」交给调用方。
    // 系统意义：「CaseOutcomeController.confirmDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/review/confirm")
    public ApiResponse<ReviewDecisionView> confirmDraft(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody OutcomeReviewDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.confirmDraft(
                        caseId,
                        body.reason(),
                        idempotencyKey,
                        actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.modifyDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「CaseOutcomeController.modifyDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」：处理「POST /review/modify」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.modifyDraft」、「ApiResponse.success」、「body.reason」、「body.approvedPlan」，并返回「ApiResponse<ReviewDecisionView>」。
    // 上游调用：「CaseOutcomeController.modifyDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /review/modify」HTTP 请求。
    // 下游影响：「CaseOutcomeController.modifyDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」向下依次触达 「service.modifyDraft」、「ApiResponse.success」、「body.reason」、「body.approvedPlan」；计算结果以「ApiResponse<ReviewDecisionView>」交给调用方。
    // 系统意义：「CaseOutcomeController.modifyDraft(String,String,OutcomeReviewDecisionRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/review/modify")
    public ApiResponse<ReviewDecisionView> modifyDraft(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody OutcomeReviewDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.modifyDraft(
                        caseId,
                        body.reason(),
                        body.approvedPlan(),
                        idempotencyKey,
                        actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.actor(Authentication)」。
    // 具体功能：「CaseOutcomeController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「CaseOutcomeController.actor(Authentication)」的上游调用点包括 「CaseOutcomeController.get」、「CaseOutcomeController.confirmDraft」、「CaseOutcomeController.modifyDraft」。
    // 下游影响：「CaseOutcomeController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「CaseOutcomeController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】「CaseOutcomeController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「CaseOutcomeController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseOutcomeController.correlationId(HttpServletRequest,String)」的上游调用点包括 「CaseOutcomeController.get」、「CaseOutcomeController.confirmDraft」、「CaseOutcomeController.modifyDraft」。
    // 下游影响：「CaseOutcomeController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「CaseOutcomeController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }

    // 所属模块：【裁决结果查询 / HTTP 接口层】类型「OutcomeReviewDecisionRequest」。
    // 类型职责：定义结果审核决定跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record OutcomeReviewDecisionRequest(
            @NotBlank @Size(max = 2000) String reason,
            JsonNode approvedPlan) {}
}
