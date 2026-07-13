/*
 * 所属模块：Agent 流式运行。
 * 文件职责：把Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「get」、「events」、「replay」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.api;

import com.example.dispute.agentstream.application.AgentRunEventView;
import com.example.dispute.agentstream.application.AgentRunQueryService;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 所属模块：【Agent 流式运行 / HTTP 接口层】类型「AgentRunController」。
// 类型职责：把Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「AgentRunController」、「get」、「events」、「replay」、「actor」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/agent-runs/{runId}")
public class AgentRunController {

    private final AgentRunQueryService queryService;
    private final AgentRunStreamEventService eventService;
    private final Clock clock;

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.AgentRunController(AgentRunQueryService,AgentRunStreamEventService,Clock)」。
    // 具体功能：「AgentRunController.AgentRunController(AgentRunQueryService,AgentRunStreamEventService,Clock)」：通过构造器接收 「queryService」(AgentRunQueryService)、「eventService」(AgentRunStreamEventService)、「clock」(Clock) 并保存为「AgentRunController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunController.AgentRunController(AgentRunQueryService,AgentRunStreamEventService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「AgentRunController.AgentRunController(AgentRunQueryService,AgentRunStreamEventService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunController.AgentRunController(AgentRunQueryService,AgentRunStreamEventService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunController(
            AgentRunQueryService queryService,
            AgentRunStreamEventService eventService,
            Clock clock) {
        this.queryService = queryService;
        this.eventService = eventService;
        this.clock = clock;
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.get(String,Authentication,HttpServletRequest)」。
    // 具体功能：「AgentRunController.get(String,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「ApiResponse.success」、「actor」、「correlationId」，并返回「ApiResponse<AgentRunView>」。
    // 上游调用：「AgentRunController.get(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「AgentRunController.get(String,Authentication,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「actor」、「correlationId」；计算结果以「ApiResponse<AgentRunView>」交给调用方。
    // 系统意义：「AgentRunController.get(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<AgentRunView> get(
            @PathVariable @Pattern(regexp = "AGENT_RUN_[A-Za-z0-9]{1,54}") String runId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                queryService.get(runId, actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.events(String,Long,Long,Authentication)」。
    // 具体功能：「AgentRunController.events(String,Long,Long,Authentication)」：处理「GET /events」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「eventService.subscribe」、「actor」，并返回「SseEmitter」。
    // 上游调用：「AgentRunController.events(String,Long,Long,Authentication)」的上游是携带认证信息与 Trace/Request ID 的「GET /events」HTTP 请求。
    // 下游影响：「AgentRunController.events(String,Long,Long,Authentication)」向下依次触达 「eventService.subscribe」、「actor」；计算结果以「SseEmitter」交给调用方。
    // 系统意义：「AgentRunController.events(String,Long,Long,Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable @Pattern(regexp = "AGENT_RUN_[A-Za-z0-9]{1,54}") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false)
                    Long lastEventId,
            @RequestParam(value = "last_event_id", required = false)
                    Long queryCursor,
            Authentication authentication) {
        long cursor =
                lastEventId != null
                        ? lastEventId
                        : queryCursor == null ? -1L : queryCursor;
        if (cursor < -1) {
            throw new IllegalArgumentException("last event id must be at least -1");
        }
        SseEmitter emitter = eventService.subscribe(runId, cursor, actor(authentication));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.replay(String,long,Authentication,HttpServletRequest)」。
    // 具体功能：「AgentRunController.replay(String,long,Authentication,HttpServletRequest)」：处理「GET /events/replay」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「eventService.replay」、「ApiResponse.success」、「actor」、「correlationId」，并返回「ApiResponse<List<AgentRunEventView>>」。
    // 上游调用：「AgentRunController.replay(String,long,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /events/replay」HTTP 请求。
    // 下游影响：「AgentRunController.replay(String,long,Authentication,HttpServletRequest)」向下依次触达 「eventService.replay」、「ApiResponse.success」、「actor」、「correlationId」；计算结果以「ApiResponse<List<AgentRunEventView>>」交给调用方。
    // 系统意义：「AgentRunController.replay(String,long,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/events/replay")
    public ApiResponse<List<AgentRunEventView>> replay(
            @PathVariable @Pattern(regexp = "AGENT_RUN_[A-Za-z0-9]{1,54}") String runId,
            @RequestParam(value = "after_sequence", defaultValue = "-1")
                    long afterSequence,
            Authentication authentication,
            HttpServletRequest request) {
        if (afterSequence < -1) {
            throw new IllegalArgumentException("after sequence must be at least -1");
        }
        return ApiResponse.success(
                eventService.replay(runId, afterSequence, actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.actor(Authentication)」。
    // 具体功能：「AgentRunController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「AgentRunController.actor(Authentication)」的上游调用点包括 「AgentRunController.get」、「AgentRunController.events」、「AgentRunController.replay」。
    // 下游影响：「AgentRunController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「AgentRunController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「AgentRunController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「AgentRunController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「AgentRunController.correlationId(HttpServletRequest,String)」的上游调用点包括 「AgentRunController.get」、「AgentRunController.replay」。
    // 下游影响：「AgentRunController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
