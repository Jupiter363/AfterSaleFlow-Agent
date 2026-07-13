/*
 * 所属模块：房间协作与权限。
 * 文件职责：把案件事件能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「subscribe」、「replay」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.CaseEventView;
import com.example.dispute.room.application.CaseEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 所属模块：【房间协作与权限 / HTTP 接口层】类型「CaseEventController」。
// 类型职责：把案件事件能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「CaseEventController」、「subscribe」、「replay」、「correlationId」。
// 协作关系：主要由 「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/events")
public class CaseEventController {

    private final CaseEventService service;
    private final Clock clock;

    // 所属模块：【房间协作与权限 / HTTP 接口层】「CaseEventController.CaseEventController(CaseEventService,Clock)」。
    // 具体功能：「CaseEventController.CaseEventController(CaseEventService,Clock)」：通过构造器接收 「service」(CaseEventService)、「clock」(Clock) 并保存为「CaseEventController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseEventController.CaseEventController(CaseEventService,Clock)」的上游创建点包括 「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild」。
    // 下游影响：「CaseEventController.CaseEventController(CaseEventService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseEventController.CaseEventController(CaseEventService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseEventController(CaseEventService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「CaseEventController.subscribe(String,Long,Long,Authentication)」。
    // 具体功能：「CaseEventController.subscribe(String,Long,Long,Authentication)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.subscribe」、「authentication.getPrincipal」，并返回「SseEmitter」。
    // 上游调用：「CaseEventController.subscribe(String,Long,Long,Authentication)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「CaseEventController.subscribe(String,Long,Long,Authentication)」向下依次触达 「service.subscribe」、「authentication.getPrincipal」；计算结果以「SseEmitter」交给调用方。
    // 系统意义：「CaseEventController.subscribe(String,Long,Long,Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestHeader(value = "Last-Event-ID", required = false) @Min(0)
                    Long lastEventId,
            @RequestParam(value = "last_event_id", required = false) @Min(0)
                    Long queryCursor,
            Authentication authentication) {
        long cursor =
                lastEventId != null
                        ? lastEventId
                        : queryCursor != null ? queryCursor : 0L;
        return service.subscribe(
                caseId,
                cursor,
                (AuthenticatedActor) authentication.getPrincipal());
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「CaseEventController.replay(String,long,Authentication,HttpServletRequest)」。
    // 具体功能：「CaseEventController.replay(String,long,Authentication,HttpServletRequest)」：处理「GET /replay」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.replay」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」，并返回「ApiResponse<List<CaseEventView>>」。
    // 上游调用：「CaseEventController.replay(String,long,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /replay」HTTP 请求。
    // 下游影响：「CaseEventController.replay(String,long,Authentication,HttpServletRequest)」向下依次触达 「service.replay」、「ApiResponse.success」、「authentication.getPrincipal」、「correlationId」；计算结果以「ApiResponse<List<CaseEventView>>」交给调用方。
    // 系统意义：「CaseEventController.replay(String,long,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/replay")
    public ApiResponse<List<CaseEventView>> replay(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestParam(value = "after_sequence", defaultValue = "0") @Min(0)
                    long afterSequence,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.replay(
                        caseId,
                        afterSequence,
                        (AuthenticatedActor) authentication.getPrincipal()),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【房间协作与权限 / HTTP 接口层】「CaseEventController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「CaseEventController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseEventController.correlationId(HttpServletRequest,String)」的上游调用点包括 「CaseEventController.replay」。
    // 下游影响：「CaseEventController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「CaseEventController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
