/*
 * 所属模块：案件核心与导入。
 * 文件职责：把争议能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「create」、「get」、「list」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CasePageView;
import com.example.dispute.caseintake.application.CaseView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Final public API for fulfillment disputes.
 *
 * <p>The production path intentionally has no URL version suffix. Schema and behavior versions
 * are carried in contracts and trace metadata so `/v2` and `/v3` APIs cannot create parallel
 * product semantics.
 */
// 所属模块：【案件核心与导入 / HTTP 接口层】类型「DisputeController」。
// 类型职责：把争议能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「DisputeController」、「create」、「get」、「list」、「actor」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final CaseApplicationService service;
    private final Clock clock;

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.DisputeController(CaseApplicationService,Clock)」。
    // 具体功能：「DisputeController.DisputeController(CaseApplicationService,Clock)」：通过构造器接收 「service」(CaseApplicationService)、「clock」(Clock) 并保存为「DisputeController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「DisputeController.DisputeController(CaseApplicationService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DisputeController.DisputeController(CaseApplicationService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DisputeController.DisputeController(CaseApplicationService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DisputeController(CaseApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.create(CreateDisputeRequest,String,Authentication,HttpServletRequest)」。
    // 具体功能：「DisputeController.create(CreateDisputeRequest,String,Authentication,HttpServletRequest)」：处理「POST /api/disputes」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.create」、「ResponseEntity.status」、「ApiResponse.success」、「request.toCommand」，并返回「ResponseEntity<ApiResponse<CaseView>>」。
    // 上游调用：「DisputeController.create(CreateDisputeRequest,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/disputes」HTTP 请求。
    // 下游影响：「DisputeController.create(CreateDisputeRequest,String,Authentication,HttpServletRequest)」向下依次触达 「service.create」、「ResponseEntity.status」、「ApiResponse.success」、「request.toCommand」；计算结果以「ResponseEntity<ApiResponse<CaseView>>」交给调用方。
    // 系统意义：「DisputeController.create(CreateDisputeRequest,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping
    public ResponseEntity<ApiResponse<CaseView>> create(
            @Valid @RequestBody CreateDisputeRequest request,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId =
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId =
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        CaseView result =
                service.create(
                        request.toCommand(),
                        actor(authentication),
                        idempotencyKey,
                        traceId,
                        requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                result,
                                requestId,
                                traceId,
                                Instant.now(clock)));
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.get(String,Authentication,HttpServletRequest)」。
    // 具体功能：「DisputeController.get(String,Authentication,HttpServletRequest)」：处理「GET /api/disputes」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「ApiResponse.success」、「correlationId」、「actor」，并返回「ApiResponse<CaseView>」。
    // 上游调用：「DisputeController.get(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/disputes」HTTP 请求。
    // 下游影响：「DisputeController.get(String,Authentication,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「correlationId」、「actor」；计算结果以「ApiResponse<CaseView>」交给调用方。
    // 系统意义：「DisputeController.get(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/{caseId}")
    public ApiResponse<CaseView> get(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId =
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId =
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.get(caseId, actor(authentication)),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.list(CaseStatus,String,int,int,Authentication,HttpServletRequest)」。
    // 具体功能：「DisputeController.list(CaseStatus,String,int,int,Authentication,HttpServletRequest)」：处理「GET /api/disputes」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.list」、「ApiResponse.success」、「correlationId」、「actor」，并返回「ApiResponse<CasePageView>」。
    // 上游调用：「DisputeController.list(CaseStatus,String,int,int,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/disputes」HTTP 请求。
    // 下游影响：「DisputeController.list(CaseStatus,String,int,int,Authentication,HttpServletRequest)」向下依次触达 「service.list」、「ApiResponse.success」、「correlationId」、「actor」；计算结果以「ApiResponse<CasePageView>」交给调用方。
    // 系统意义：「DisputeController.list(CaseStatus,String,int,int,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<CasePageView> list(
            @RequestParam(required = false)
                    com.example.dispute.domain.model.CaseStatus status,
            @RequestParam(name = "dispute_type", required = false)
                    @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}")
                    String disputeType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId =
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId =
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.list(
                        status,
                        disputeType,
                        page,
                        size,
                        actor(authentication)),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.actor(Authentication)」。
    // 具体功能：「DisputeController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「DisputeController.actor(Authentication)」的上游调用点包括 「DisputeController.create」、「DisputeController.get」、「DisputeController.list」。
    // 下游影响：「DisputeController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「DisputeController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「DisputeController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「DisputeController.correlationId(HttpServletRequest,String)」的上游调用点包括 「DisputeController.create」、「DisputeController.get」、「DisputeController.list」。
    // 下游影响：「DisputeController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「DisputeController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
