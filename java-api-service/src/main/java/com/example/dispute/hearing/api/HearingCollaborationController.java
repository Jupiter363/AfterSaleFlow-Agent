/*
 * 所属模块：共享小法庭。
 * 文件职责：把庭审Collaboration能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「hearing」、「rounds」、「complete」、「completeRound」、「submitCurrentRound」、「settlements」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingCourtBootstrapService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingRoundView;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【共享小法庭 / HTTP 接口层】类型「HearingCollaborationController」。
// 类型职责：把庭审Collaboration能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「HearingCollaborationController」、「hearing」、「rounds」、「complete」、「completeRound」、「submitCurrentRound」。
// 协作关系：主要由 「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus」、「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/hearing")
public class HearingCollaborationController {

    private final HearingRoundService roundService;
    private final SettlementService settlementService;
    private final HearingCourtBootstrapService bootstrapService;
    private final Clock clock;

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.HearingCollaborationController(HearingRoundService,SettlementService,HearingCourtBootstrapService,Clock)」。
    // 具体功能：「HearingCollaborationController.HearingCollaborationController(HearingRoundService,SettlementService,HearingCourtBootstrapService,Clock)」：通过构造器接收 「roundService」(HearingRoundService)、「settlementService」(SettlementService)、「bootstrapService」(HearingCourtBootstrapService)、「clock」(Clock) 并保存为「HearingCollaborationController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingCollaborationController.HearingCollaborationController(HearingRoundService,SettlementService,HearingCourtBootstrapService,Clock)」的上游创建点包括 「HearingCollaborationControllerTest.hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel」、「HearingCollaborationControllerTest.completeEndpointReturnsTheServerBackedHearingStatus」。
    // 下游影响：「HearingCollaborationController.HearingCollaborationController(HearingRoundService,SettlementService,HearingCourtBootstrapService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCollaborationController.HearingCollaborationController(HearingRoundService,SettlementService,HearingCourtBootstrapService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingCollaborationController(
            HearingRoundService roundService,
            SettlementService settlementService,
            HearingCourtBootstrapService bootstrapService,
            Clock clock) {
        this.roundService = roundService;
        this.settlementService = settlementService;
        this.bootstrapService = bootstrapService;
        this.clock = clock;
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.hearing(String,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.hearing(String,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「bootstrapService.bootstrap」、「roundService.list」、「settlementService.list」、「roundService.status」，并返回「ApiResponse<Map<String, Object>>」。
    // 上游调用：「HearingCollaborationController.hearing(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「HearingCollaborationController.hearing(String,Authentication,HttpServletRequest)」向下依次触达 「bootstrapService.bootstrap」、「roundService.list」、「settlementService.list」、「roundService.status」；计算结果以「ApiResponse<Map<String, Object>>」交给调用方。
    // 系统意义：「HearingCollaborationController.hearing(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<Map<String, Object>> hearing(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        AuthenticatedActor actor = actor(authentication);
        bootstrapService.bootstrap(
                caseId, actor, correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE));
        return success(
                Map.of(
                        "rounds", roundService.list(caseId, actor),
                        "settlements", settlementService.list(caseId, actor),
                        "status", roundService.status(caseId, actor)),
                request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.rounds(String,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.rounds(String,Authentication,HttpServletRequest)」：处理「GET /rounds」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「roundService.list」、「success」、「actor」，并返回「ApiResponse<List<HearingRoundView>>」。
    // 上游调用：「HearingCollaborationController.rounds(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /rounds」HTTP 请求。
    // 下游影响：「HearingCollaborationController.rounds(String,Authentication,HttpServletRequest)」向下依次触达 「roundService.list」、「success」、「actor」；计算结果以「ApiResponse<List<HearingRoundView>>」交给调用方。
    // 系统意义：「HearingCollaborationController.rounds(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/rounds")
    public ApiResponse<List<HearingRoundView>> rounds(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(roundService.list(caseId, actor(authentication)), request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.complete(String,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.complete(String,Authentication,HttpServletRequest)」：处理「POST /complete」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「roundService.completeHearing」、「success」、「actor」，并返回「ApiResponse<com.example.dispute.hearing.application.HearingStatusView>」。
    // 上游调用：「HearingCollaborationController.complete(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /complete」HTTP 请求。
    // 下游影响：「HearingCollaborationController.complete(String,Authentication,HttpServletRequest)」向下依次触达 「roundService.completeHearing」、「success」、「actor」；计算结果以「ApiResponse<com.example.dispute.hearing.application.HearingStatusView>」交给调用方。
    // 系统意义：「HearingCollaborationController.complete(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/complete")
    public ApiResponse<com.example.dispute.hearing.application.HearingStatusView> complete(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(roundService.completeHearing(caseId, actor(authentication)), request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.completeRound(String,CompleteHearingRoundRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.completeRound(String,CompleteHearingRoundRequest,Authentication,HttpServletRequest)」：处理「POST /rounds/complete」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「roundService.completeNext」、「body.toCommand」、「success」、「actor」，并返回「ApiResponse<HearingRoundView>」。
    // 上游调用：「HearingCollaborationController.completeRound(String,CompleteHearingRoundRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /rounds/complete」HTTP 请求。
    // 下游影响：「HearingCollaborationController.completeRound(String,CompleteHearingRoundRequest,Authentication,HttpServletRequest)」向下依次触达 「roundService.completeNext」、「body.toCommand」、「success」、「actor」；计算结果以「ApiResponse<HearingRoundView>」交给调用方。
    // 系统意义：「HearingCollaborationController.completeRound(String,CompleteHearingRoundRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/rounds/complete")
    public ApiResponse<HearingRoundView> completeRound(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody CompleteHearingRoundRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                roundService.completeNext(
                        caseId, body.toCommand(), actor(authentication)),
                request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.submitCurrentRound(String,SubmitHearingRoundRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.submitCurrentRound(String,SubmitHearingRoundRequest,Authentication,HttpServletRequest)」：处理「POST /rounds/current/submissions」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「roundService.submitParty」、「body.toCommand」、「success」、「actor」，并返回「ApiResponse<HearingRoundView>」。
    // 上游调用：「HearingCollaborationController.submitCurrentRound(String,SubmitHearingRoundRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /rounds/current/submissions」HTTP 请求。
    // 下游影响：「HearingCollaborationController.submitCurrentRound(String,SubmitHearingRoundRequest,Authentication,HttpServletRequest)」向下依次触达 「roundService.submitParty」、「body.toCommand」、「success」、「actor」；计算结果以「ApiResponse<HearingRoundView>」交给调用方。
    // 系统意义：「HearingCollaborationController.submitCurrentRound(String,SubmitHearingRoundRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/rounds/current/submissions")
    public ApiResponse<HearingRoundView> submitCurrentRound(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody SubmitHearingRoundRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                roundService.submitParty(caseId, body.toCommand(), actor(authentication)),
                request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.settlements(String,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.settlements(String,Authentication,HttpServletRequest)」：处理「GET /settlements」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「settlementService.list」、「success」、「actor」，并返回「ApiResponse<List<SettlementView>>」。
    // 上游调用：「HearingCollaborationController.settlements(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /settlements」HTTP 请求。
    // 下游影响：「HearingCollaborationController.settlements(String,Authentication,HttpServletRequest)」向下依次触达 「settlementService.list」、「success」、「actor」；计算结果以「ApiResponse<List<SettlementView>>」交给调用方。
    // 系统意义：「HearingCollaborationController.settlements(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/settlements")
    public ApiResponse<List<SettlementView>> settlements(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(settlementService.list(caseId, actor(authentication)), request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.proposeSettlement(String,SettlementProposalRequest,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.proposeSettlement(String,SettlementProposalRequest,Authentication,HttpServletRequest)」：处理「POST /settlements」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「settlementService.propose」、「body.toCommand」、「success」、「actor」，并返回「ApiResponse<SettlementView>」。
    // 上游调用：「HearingCollaborationController.proposeSettlement(String,SettlementProposalRequest,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /settlements」HTTP 请求。
    // 下游影响：「HearingCollaborationController.proposeSettlement(String,SettlementProposalRequest,Authentication,HttpServletRequest)」向下依次触达 「settlementService.propose」、「body.toCommand」、「success」、「actor」；计算结果以「ApiResponse<SettlementView>」交给调用方。
    // 系统意义：「HearingCollaborationController.proposeSettlement(String,SettlementProposalRequest,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/settlements")
    public ApiResponse<SettlementView> proposeSettlement(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody SettlementProposalRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                settlementService.propose(
                        caseId,
                        body.toCommand(),
                        actor(authentication),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.confirmSettlement(String,int,String,Authentication,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.confirmSettlement(String,int,String,Authentication,HttpServletRequest)」：处理「POST /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「settlementService.confirm」、「success」、「actor」，并返回「ApiResponse<SettlementView>」。
    // 上游调用：「HearingCollaborationController.confirmSettlement(String,int,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /」HTTP 请求。
    // 下游影响：「HearingCollaborationController.confirmSettlement(String,int,String,Authentication,HttpServletRequest)」向下依次触达 「settlementService.confirm」、「success」、「actor」；计算结果以「ApiResponse<SettlementView>」交给调用方。
    // 系统意义：「HearingCollaborationController.confirmSettlement(String,int,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/settlements/{version}/confirm")
    public ApiResponse<SettlementView> confirmSettlement(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                settlementService.confirm(
                        caseId, version, actor(authentication), idempotencyKey),
                request);
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.success(T,HttpServletRequest)」。
    // 具体功能：「HearingCollaborationController.success(T,HttpServletRequest)」：构建成功；实际协作者为 「ApiResponse.success」、「correlationId」，最终返回「ApiResponse<T>」。
    // 上游调用：「HearingCollaborationController.success(T,HttpServletRequest)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCollaborationController.rounds」、「HearingCollaborationController.complete」、「HearingCollaborationController.completeRound」。
    // 下游影响：「HearingCollaborationController.success(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「correlationId」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「HearingCollaborationController.success(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.actor(Authentication)」。
    // 具体功能：「HearingCollaborationController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「HearingCollaborationController.actor(Authentication)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCollaborationController.rounds」、「HearingCollaborationController.complete」、「HearingCollaborationController.completeRound」。
    // 下游影响：「HearingCollaborationController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「HearingCollaborationController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【共享小法庭 / HTTP 接口层】「HearingCollaborationController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「HearingCollaborationController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「HearingCollaborationController.correlationId(HttpServletRequest,String)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCollaborationController.proposeSettlement」、「HearingCollaborationController.success」。
    // 下游影响：「HearingCollaborationController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCollaborationController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
