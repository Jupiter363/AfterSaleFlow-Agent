/*
 * 所属模块：案件核心与导入。
 * 文件职责：把争议导入模拟能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「simulateImport」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.SimulatedImportResultView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public demo adapter for importing externally-originated dispute cases.
 *
 * <p>The browser should never call the internal import boundary directly. This controller validates
 * the current demo actor and then delegates to the internal import workflow with a system service
 * identity, matching how a trusted OMS/after-sale adapter would enter the platform.
 */
// 所属模块：【案件核心与导入 / HTTP 接口层】类型「DisputeImportSimulationController」。
// 类型职责：把争议导入模拟能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「DisputeImportSimulationController」、「simulateImport」、「assertCanSimulateForCurrentIdentity」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/import")
public class DisputeImportSimulationController {

    private static final AuthenticatedActor SYSTEM_IMPORT_ACTOR =
            new AuthenticatedActor("external-import-simulator", ActorRole.SYSTEM);

    private final DisputeImportService service;
    private final Clock clock;

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeImportSimulationController.DisputeImportSimulationController(DisputeImportService,Clock)」。
    // 具体功能：「DisputeImportSimulationController.DisputeImportSimulationController(DisputeImportService,Clock)」：通过构造器接收 「service」(DisputeImportService)、「clock」(Clock) 并保存为「DisputeImportSimulationController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「DisputeImportSimulationController.DisputeImportSimulationController(DisputeImportService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DisputeImportSimulationController.DisputeImportSimulationController(DisputeImportService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DisputeImportSimulationController.DisputeImportSimulationController(DisputeImportService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DisputeImportSimulationController(DisputeImportService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeImportSimulationController.simulateImport(SimulateImportRequest,String,Authentication,HttpServletRequest)」。
    // 具体功能：「DisputeImportSimulationController.simulateImport(SimulateImportRequest,String,Authentication,HttpServletRequest)」：处理「POST /api/disputes/import/simulate」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.simulateExternalImport」、「ResponseEntity.status」、「ApiResponse.success」、「authentication.getPrincipal」，并返回「ResponseEntity<ApiResponse<SimulatedImportResultView>>」。
    // 上游调用：「DisputeImportSimulationController.simulateImport(SimulateImportRequest,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/disputes/import/simulate」HTTP 请求。
    // 下游影响：「DisputeImportSimulationController.simulateImport(SimulateImportRequest,String,Authentication,HttpServletRequest)」向下依次触达 「service.simulateExternalImport」、「ResponseEntity.status」、「ApiResponse.success」、「authentication.getPrincipal」；计算结果以「ResponseEntity<ApiResponse<SimulatedImportResultView>>」交给调用方。
    // 系统意义：「DisputeImportSimulationController.simulateImport(SimulateImportRequest,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/simulate")
    public ResponseEntity<ApiResponse<SimulatedImportResultView>> simulateImport(
            @Valid @RequestBody SimulateImportRequest request,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        AuthenticatedActor actor = (AuthenticatedActor) authentication.getPrincipal();
        assertCanSimulateForCurrentIdentity(request, actor);
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        SimulatedImportResultView imported =
                service.simulateExternalImport(
                        request.toCommand(idempotencyKey),
                        SYSTEM_IMPORT_ACTOR,
                        idempotencyKey,
                        traceId,
                        requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(imported, requestId, traceId, Instant.now(clock)));
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeImportSimulationController.assertCanSimulateForCurrentIdentity(SimulateImportRequest,AuthenticatedActor)」。
    // 具体功能：「DisputeImportSimulationController.assertCanSimulateForCurrentIdentity(SimulateImportRequest,AuthenticatedActor)」：断言CanSimulate面向CurrentIdentity；实际协作者为 「actor.role」、「request.initiatorRoleHint」、「actor.actorId」、「request.currentActorId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「DisputeImportSimulationController.assertCanSimulateForCurrentIdentity(SimulateImportRequest,AuthenticatedActor)」的上游调用点包括 「DisputeImportSimulationController.simulateImport」。
    // 下游影响：「DisputeImportSimulationController.assertCanSimulateForCurrentIdentity(SimulateImportRequest,AuthenticatedActor)」向下依次触达 「actor.role」、「request.initiatorRoleHint」、「actor.actorId」、「request.currentActorId」。
    // 系统意义：「DisputeImportSimulationController.assertCanSimulateForCurrentIdentity(SimulateImportRequest,AuthenticatedActor)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static void assertCanSimulateForCurrentIdentity(
            SimulateImportRequest request, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            throw new ForbiddenException("only user or merchant demo identities can simulate import");
        }
        if (request.initiatorRoleHint() != actor.role()) {
            throw new ForbiddenException("initiator role must match the current demo identity");
        }
        if (!actor.actorId().equals(request.currentActorId())) {
            throw new ForbiddenException("current actor id must match the current demo identity");
        }
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「DisputeImportSimulationController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「DisputeImportSimulationController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「DisputeImportSimulationController.correlationId(HttpServletRequest,String)」的上游调用点包括 「DisputeImportSimulationController.simulateImport」。
    // 下游影响：「DisputeImportSimulationController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「DisputeImportSimulationController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
