/*
 * 所属模块：平台人工终审。
 * 文件职责：把审核能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「list」、「packet」、「decide」、「queryCopilot」、「activeCopilotRuns」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.api;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ReviewTaskStatus;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewCopilotStreamService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.example.dispute.review.application.ReviewPacketView;
import com.example.dispute.review.application.ReviewTaskView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

// 所属模块：【平台人工终审 / HTTP 接口层】类型「ReviewController」。
// 类型职责：把审核能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「ReviewController」、「list」、「packet」、「decide」、「queryCopilot」、「activeCopilotRuns」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewApplicationService service;
    private final ReviewCopilotStreamService copilotStreamService;
    private final Clock clock;
    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.ReviewController(ReviewApplicationService,ReviewCopilotStreamService,Clock)」。
    // 具体功能：「ReviewController.ReviewController(ReviewApplicationService,ReviewCopilotStreamService,Clock)」：通过构造器接收 「service」(ReviewApplicationService)、「copilotStreamService」(ReviewCopilotStreamService)、「clock」(Clock) 并保存为「ReviewController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ReviewController.ReviewController(ReviewApplicationService,ReviewCopilotStreamService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ReviewController.ReviewController(ReviewApplicationService,ReviewCopilotStreamService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewController.ReviewController(ReviewApplicationService,ReviewCopilotStreamService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ReviewController(
            ReviewApplicationService service,
            ReviewCopilotStreamService copilotStreamService,
            Clock clock) {
        this.service = service;
        this.copilotStreamService = copilotStreamService;
        this.clock = clock;
    }

    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.list(ReviewTaskStatus,Authentication,HttpServletRequest)」。
    // 具体功能：「ReviewController.list(ReviewTaskStatus,Authentication,HttpServletRequest)」：处理「GET /api/reviews」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.list」、「success」、「actor」，并返回「ApiResponse<List<ReviewTaskView>>」。
    // 上游调用：「ReviewController.list(ReviewTaskStatus,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/reviews」HTTP 请求。
    // 下游影响：「ReviewController.list(ReviewTaskStatus,Authentication,HttpServletRequest)」向下依次触达 「service.list」、「success」、「actor」；计算结果以「ApiResponse<List<ReviewTaskView>>」交给调用方。
    // 系统意义：「ReviewController.list(ReviewTaskStatus,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<List<ReviewTaskView>> list(
            @RequestParam(defaultValue="PENDING") ReviewTaskStatus status,
            Authentication authentication,HttpServletRequest request){
        return success(service.list(status,actor(authentication)),request);
    }
    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.packet(String,Authentication,HttpServletRequest)」。
    // 具体功能：「ReviewController.packet(String,Authentication,HttpServletRequest)」：处理「GET /api/reviews」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.packet」、「success」、「actor」，并返回「ApiResponse<ReviewPacketView>」。
    // 上游调用：「ReviewController.packet(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/reviews」HTTP 请求。
    // 下游影响：「ReviewController.packet(String,Authentication,HttpServletRequest)」向下依次触达 「service.packet」、「success」、「actor」；计算结果以「ApiResponse<ReviewPacketView>」交给调用方。
    // 系统意义：「ReviewController.packet(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/{taskId}/packet")
    public ApiResponse<ReviewPacketView> packet(
            @PathVariable @NotBlank String taskId,Authentication authentication,HttpServletRequest request){
        return success(service.packet(taskId,actor(authentication)),request);
    }
    @PostMapping("/{taskId}/start")
    public ApiResponse<ReviewTaskView> start(
            @PathVariable @NotBlank String taskId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(service.start(taskId, actor(authentication)), request);
    }
    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.decide(String,String,DecisionRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「ReviewController.decide(String,String,DecisionRequest,Authentication,HttpServletRequest)」：处理「POST /api/reviews」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.decide」、「body.decision」、「body.reason」、「body.approvedPlan」，并返回「ApiResponse<ReviewDecisionView>」。
    // 上游调用：「ReviewController.decide(String,String,DecisionRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/reviews」HTTP 请求。
    // 下游影响：「ReviewController.decide(String,String,DecisionRequest,Authentication,HttpServletRequest)」向下依次触达 「service.decide」、「body.decision」、「body.reason」、「body.approvedPlan」；计算结果以「ApiResponse<ReviewDecisionView>」交给调用方。
    // 系统意义：「ReviewController.decide(String,String,DecisionRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/{taskId}/decision")
    public ApiResponse<ReviewDecisionView> decide(
            @PathVariable @NotBlank String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody DecisionRequest body,
            Authentication authentication,HttpServletRequest request){
        return success(service.decide(taskId,new ReviewDecisionCommand(
                body.decision(),body.reason(),body.approvedPlan(),idempotencyKey),actor(authentication)),request);
    }

    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.queryCopilot(String,String,CopilotQueryRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「ReviewController.queryCopilot(String,String,CopilotQueryRequest,Authentication,HttpServletRequest)」：处理「POST /api/reviews」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「copilotStreamService.query」、「body.question」、「success」、「correlationId」，并返回「ApiResponse<AgentRunAcceptedView>」。
    // 上游调用：「ReviewController.queryCopilot(String,String,CopilotQueryRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/reviews」HTTP 请求。
    // 下游影响：「ReviewController.queryCopilot(String,String,CopilotQueryRequest,Authentication,HttpServletRequest)」向下依次触达 「copilotStreamService.query」、「body.question」、「success」、「correlationId」；计算结果以「ApiResponse<AgentRunAcceptedView>」交给调用方。
    // 系统意义：「ReviewController.queryCopilot(String,String,CopilotQueryRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/{taskId}/copilot/query")
    public ApiResponse<AgentRunAcceptedView> queryCopilot(
            @PathVariable @NotBlank String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CopilotQueryRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                copilotStreamService.query(
                        taskId,
                        body.question(),
                        idempotencyKey,
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                        correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                        actor(authentication)),
                request);
    }

    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.activeCopilotRuns(String,Authentication,HttpServletRequest)」。
    // 具体功能：「ReviewController.activeCopilotRuns(String,Authentication,HttpServletRequest)」：处理「GET /api/reviews」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「copilotStreamService.active」、「success」、「actor」，并返回「ApiResponse<List<AgentRunView>>」。
    // 上游调用：「ReviewController.activeCopilotRuns(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/reviews」HTTP 请求。
    // 下游影响：「ReviewController.activeCopilotRuns(String,Authentication,HttpServletRequest)」向下依次触达 「copilotStreamService.active」、「success」、「actor」；计算结果以「ApiResponse<List<AgentRunView>>」交给调用方。
    // 系统意义：「ReviewController.activeCopilotRuns(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/{taskId}/copilot/active")
    public ApiResponse<List<AgentRunView>> activeCopilotRuns(
            @PathVariable @NotBlank String taskId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                copilotStreamService.active(taskId, actor(authentication)),
                request);
    }

    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.success(T,HttpServletRequest)」。
    // 具体功能：「ReviewController.success(T,HttpServletRequest)」：构建成功；实际协作者为 「ApiResponse.success」、「request.getAttribute」，最终返回「ApiResponse<T>」。
    // 上游调用：「ReviewController.success(T,HttpServletRequest)」的上游调用点包括 「ReviewController.list」、「ReviewController.packet」、「ReviewController.decide」、「ReviewController.queryCopilot」。
    // 下游影响：「ReviewController.success(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「request.getAttribute」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「ReviewController.success(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> success(T data,HttpServletRequest request){return ApiResponse.success(
            data,(String)request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
            (String)request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),Instant.now(clock));}
    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「ReviewController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「ReviewController.correlationId(HttpServletRequest,String)」的上游调用点包括 「ReviewController.queryCopilot」。
    // 下游影响：「ReviewController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「ReviewController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }
    // 所属模块：【平台人工终审 / HTTP 接口层】「ReviewController.actor(Authentication)」。
    // 具体功能：「ReviewController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「auth.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「ReviewController.actor(Authentication)」的上游调用点包括 「ReviewController.list」、「ReviewController.packet」、「ReviewController.decide」、「ReviewController.queryCopilot」。
    // 下游影响：「ReviewController.actor(Authentication)」向下依次触达 「auth.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「ReviewController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication auth){return (AuthenticatedActor)auth.getPrincipal();}
    // 所属模块：【平台人工终审 / HTTP 接口层】类型「DecisionRequest」。
    // 类型职责：定义决定跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record DecisionRequest(
            @NotNull ApprovalDecisionType decision,
            @NotBlank @Size(max=2000) String reason,
            JsonNode approvedPlan){}
    // 所属模块：【平台人工终审 / HTTP 接口层】类型「CopilotQueryRequest」。
    // 类型职责：定义CopilotQuery跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record CopilotQueryRequest(
            @NotBlank @Size(max=20000) String question) {}
}
