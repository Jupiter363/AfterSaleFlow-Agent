/*
 * 所属模块：房间协作与权限。
 * 文件职责：把房间能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「post」、「opening」、「list」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.RoomType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【房间协作与权限 / HTTP 接口层】类型「RoomController」。
// 类型职责：把房间能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「RoomController」、「post」、「opening」、「list」、「actor」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/rooms/{roomType}/messages")
public class RoomController {

    private final RoomMessageService service;
    private final Clock clock;

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.RoomController(RoomMessageService,Clock)」。
    // 具体功能：「RoomController.RoomController(RoomMessageService,Clock)」：通过构造器接收 「service」(RoomMessageService)、「clock」(Clock) 并保存为「RoomController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RoomController.RoomController(RoomMessageService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「RoomController.RoomController(RoomMessageService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomController.RoomController(RoomMessageService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RoomController(RoomMessageService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.post(String,RoomType,RoomMessageRequest,String,Authentication,HttpServletRequest)」。
    // 具体功能：「RoomController.post(String,RoomType,RoomMessageRequest,String,Authentication,HttpServletRequest)」：处理「POST /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.post」、「ResponseEntity.status」、「ApiResponse.success」、「request.toCommand」，并返回「ResponseEntity<ApiResponse<RoomMessageView>>」。
    // 上游调用：「RoomController.post(String,RoomType,RoomMessageRequest,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /」HTTP 请求。
    // 下游影响：「RoomController.post(String,RoomType,RoomMessageRequest,String,Authentication,HttpServletRequest)」向下依次触达 「service.post」、「ResponseEntity.status」、「ApiResponse.success」、「request.toCommand」；计算结果以「ResponseEntity<ApiResponse<RoomMessageView>>」交给调用方。
    // 系统意义：「RoomController.post(String,RoomType,RoomMessageRequest,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping
    public ResponseEntity<ApiResponse<RoomMessageView>> post(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable RoomType roomType,
            @Valid @RequestBody RoomMessageRequest request,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        RoomMessageView message =
                service.post(
                        caseId,
                        roomType,
                        request.toCommand(),
                        actor(authentication),
                        idempotencyKey,
                        traceId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                message, requestId, traceId, Instant.now(clock)));
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.opening(String,RoomType,Authentication,HttpServletRequest)」。
    // 具体功能：「RoomController.opening(String,RoomType,Authentication,HttpServletRequest)」：处理「POST /opening」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.ensureOpening」、「ApiResponse.success」、「correlationId」、「actor」，并返回「ApiResponse<Object>」。
    // 上游调用：「RoomController.opening(String,RoomType,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /opening」HTTP 请求。
    // 下游影响：「RoomController.opening(String,RoomType,Authentication,HttpServletRequest)」向下依次触达 「service.ensureOpening」、「ApiResponse.success」、「correlationId」、「actor」；计算结果以「ApiResponse<Object>」交给调用方。
    // 系统意义：「RoomController.opening(String,RoomType,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/opening")
    public ApiResponse<Object> opening(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable RoomType roomType,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.ensureOpening(caseId, roomType, actor(authentication), traceId, requestId),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.list(String,RoomType,Authentication,HttpServletRequest)」。
    // 具体功能：「RoomController.list(String,RoomType,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.list」、「ApiResponse.success」、「correlationId」、「actor」，并返回「ApiResponse<List<RoomMessageView>>」。
    // 上游调用：「RoomController.list(String,RoomType,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「RoomController.list(String,RoomType,Authentication,HttpServletRequest)」向下依次触达 「service.list」、「ApiResponse.success」、「correlationId」、「actor」；计算结果以「ApiResponse<List<RoomMessageView>>」交给调用方。
    // 系统意义：「RoomController.list(String,RoomType,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<List<RoomMessageView>> list(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable RoomType roomType,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.list(caseId, roomType, actor(authentication)),
                requestId,
                traceId,
                Instant.now(clock));
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.actor(Authentication)」。
    // 具体功能：「RoomController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「RoomController.actor(Authentication)」的上游调用点包括 「RoomController.post」、「RoomController.opening」、「RoomController.list」。
    // 下游影响：「RoomController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「RoomController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「RoomController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「RoomController.correlationId(HttpServletRequest,String)」的上游调用点包括 「RoomController.post」、「RoomController.opening」、「RoomController.list」。
    // 下游影响：「RoomController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「RoomController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }
}
