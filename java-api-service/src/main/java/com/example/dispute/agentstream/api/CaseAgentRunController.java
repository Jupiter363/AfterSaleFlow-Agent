/*
 * 所属模块：Agent 流式运行。
 * 文件职责：把案件Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「active」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.api;

import com.example.dispute.agentstream.application.AgentRunQueryService;
import com.example.dispute.agentstream.application.AgentRunView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【Agent 流式运行 / HTTP 接口层】类型「CaseAgentRunController」。
// 类型职责：把案件Agent运行能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「CaseAgentRunController」、「active」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/rooms/{roomType}/agent-runs")
public class CaseAgentRunController {

    private final AgentRunQueryService queryService;
    private final CaseRoomRepository roomRepository;
    private final AccessSessionResolver accessSessionResolver;
    private final SessionPermissionService permissionService;
    private final Clock clock;

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「CaseAgentRunController.CaseAgentRunController(AgentRunQueryService,CaseRoomRepository,AccessSessionResolver,SessionPermissionService,Clock)」。
    // 具体功能：「CaseAgentRunController.CaseAgentRunController(AgentRunQueryService,CaseRoomRepository,AccessSessionResolver,SessionPermissionService,Clock)」：通过构造器接收 「queryService」(AgentRunQueryService)、「roomRepository」(CaseRoomRepository)、「accessSessionResolver」(AccessSessionResolver)、「permissionService」(SessionPermissionService)、「clock」(Clock) 并保存为「CaseAgentRunController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseAgentRunController.CaseAgentRunController(AgentRunQueryService,CaseRoomRepository,AccessSessionResolver,SessionPermissionService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「CaseAgentRunController.CaseAgentRunController(AgentRunQueryService,CaseRoomRepository,AccessSessionResolver,SessionPermissionService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseAgentRunController.CaseAgentRunController(AgentRunQueryService,CaseRoomRepository,AccessSessionResolver,SessionPermissionService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseAgentRunController(
            AgentRunQueryService queryService,
            CaseRoomRepository roomRepository,
            AccessSessionResolver accessSessionResolver,
            SessionPermissionService permissionService,
            Clock clock) {
        this.queryService = queryService;
        this.roomRepository = roomRepository;
        this.accessSessionResolver = accessSessionResolver;
        this.permissionService = permissionService;
        this.clock = clock;
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「CaseAgentRunController.active(String,RoomType,Authentication,HttpServletRequest)」。
    // 具体功能：「CaseAgentRunController.active(String,RoomType,Authentication,HttpServletRequest)」：处理「GET /active」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「permissionService.requireRoomRead」、「accessSessionResolver.resolve」、「roomRepository.findByCaseIdAndRoomType」、「queryService.active」，并返回「ApiResponse<List<AgentRunView>>」。
    // 上游调用：「CaseAgentRunController.active(String,RoomType,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /active」HTTP 请求。
    // 下游影响：「CaseAgentRunController.active(String,RoomType,Authentication,HttpServletRequest)」向下依次触达 「permissionService.requireRoomRead」、「accessSessionResolver.resolve」、「roomRepository.findByCaseIdAndRoomType」、「queryService.active」；计算结果以「ApiResponse<List<AgentRunView>>」交给调用方。
    // 系统意义：「CaseAgentRunController.active(String,RoomType,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @GetMapping("/active")
    public ApiResponse<List<AgentRunView>> active(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable RoomType roomType,
            Authentication authentication,
            HttpServletRequest request) {
        AuthenticatedActor actor = (AuthenticatedActor) authentication.getPrincipal();
        permissionService.requireRoomRead(
                accessSessionResolver.resolve(caseId, actor), roomType);
        String roomId =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"))
                        .getId();
        return ApiResponse.success(
                queryService.active(caseId, roomId, actor),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【Agent 流式运行 / HTTP 接口层】「CaseAgentRunController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「CaseAgentRunController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseAgentRunController.correlationId(HttpServletRequest,String)」的上游调用点包括 「CaseAgentRunController.active」。
    // 下游影响：「CaseAgentRunController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「CaseAgentRunController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }
}
