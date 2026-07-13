/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：把内部证据能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「content」、「applyParseResult」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceContentView;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

// 所属模块：【证据与版本化卷宗 / HTTP 接口层】类型「InternalEvidenceController」。
// 类型职责：把内部证据能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「InternalEvidenceController」、「content」、「applyParseResult」、「requireOcrSecret」、「requireJavaServiceSecret」、「requireSecret」。
// 协作关系：主要由 「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence」、「InternalEvidenceControllerTest.setUp」、「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/internal/evidence")
public class InternalEvidenceController {

    private final EvidenceParseResultService service;
    private final EvidenceApplicationService evidenceService;
    private final AppProperties properties;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.InternalEvidenceController(EvidenceParseResultService,EvidenceApplicationService,AppProperties,Clock)」。
    // 具体功能：「InternalEvidenceController.InternalEvidenceController(EvidenceParseResultService,EvidenceApplicationService,AppProperties,Clock)」：通过构造器接收 「service」(EvidenceParseResultService)、「evidenceService」(EvidenceApplicationService)、「properties」(AppProperties)、「clock」(Clock) 并保存为「InternalEvidenceController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「InternalEvidenceController.InternalEvidenceController(EvidenceParseResultService,EvidenceApplicationService,AppProperties,Clock)」的上游创建点包括 「InternalEvidenceControllerTest.setUp」。
    // 下游影响：「InternalEvidenceController.InternalEvidenceController(EvidenceParseResultService,EvidenceApplicationService,AppProperties,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「InternalEvidenceController.InternalEvidenceController(EvidenceParseResultService,EvidenceApplicationService,AppProperties,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public InternalEvidenceController(
            EvidenceParseResultService service,
            EvidenceApplicationService evidenceService,
            AppProperties properties,
            Clock clock) {
        this.service = service;
        this.evidenceService = evidenceService;
        this.properties = properties;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.content(String,String,String,Authentication)」。
    // 具体功能：「InternalEvidenceController.content(String,String,String,Authentication)」：处理「GET /internal/evidence」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「evidenceService.contentForModel」、「ResponseEntity.ok」、「ContentDisposition.attachment」、「MediaType.parseMediaType」，并返回「ResponseEntity<byte[]>」。
    // 上游调用：「InternalEvidenceController.content(String,String,String,Authentication)」的上游是携带认证信息与 Trace/Request ID 的「GET /internal/evidence」HTTP 请求。
    // 下游影响：「InternalEvidenceController.content(String,String,String,Authentication)」向下依次触达 「evidenceService.contentForModel」、「ResponseEntity.ok」、「ContentDisposition.attachment」、「MediaType.parseMediaType」；计算结果以「ResponseEntity<byte[]>」交给调用方。
    // 系统意义：「InternalEvidenceController.content(String,String,String,Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/{caseId}/{evidenceId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            @RequestHeader("X-Service-Secret") String serviceSecret,
            Authentication authentication) {
        requireJavaServiceSecret(serviceSecret);
        EvidenceContentView content =
                evidenceService.contentForModel(
                        caseId,
                        evidenceId,
                        (AuthenticatedActor) authentication.getPrincipal());
        String filename =
                content.filename() == null || content.filename().isBlank()
                        ? evidenceId
                        : content.filename();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(
                        MediaType.parseMediaType(
                                content.contentType() == null || content.contentType().isBlank()
                                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                        : content.contentType()))
                .body(content.content());
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.applyParseResult(String,ParseResultRequest,String,Authentication,HttpServletRequest)」。
    // 具体功能：「InternalEvidenceController.applyParseResult(String,ParseResultRequest,String,Authentication,HttpServletRequest)」：处理「POST /internal/evidence」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.apply」、「ApiResponse.success」、「request.toCommand」、「authentication.getPrincipal」，并返回「ApiResponse<Void>」。
    // 上游调用：「InternalEvidenceController.applyParseResult(String,ParseResultRequest,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /internal/evidence」HTTP 请求。
    // 下游影响：「InternalEvidenceController.applyParseResult(String,ParseResultRequest,String,Authentication,HttpServletRequest)」向下依次触达 「service.apply」、「ApiResponse.success」、「request.toCommand」、「authentication.getPrincipal」；计算结果以「ApiResponse<Void>」交给调用方。
    // 系统意义：「InternalEvidenceController.applyParseResult(String,ParseResultRequest,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/{evidenceId}/parse-result")
    public ApiResponse<Void> applyParseResult(
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            @Valid @RequestBody ParseResultRequest request,
            @RequestHeader("X-Service-Secret") String serviceSecret,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        requireOcrSecret(serviceSecret);
        service.apply(
                evidenceId,
                request.toCommand(),
                (AuthenticatedActor) authentication.getPrincipal());
        return ApiResponse.success(
                null,
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.requireOcrSecret(String)」。
    // 具体功能：「InternalEvidenceController.requireOcrSecret(String)」：强制校验OCRSecret；实际协作者为 「MessageDigest.isEqual」、「properties.ocr」、「properties.ocr().serviceSecret」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「InternalEvidenceController.requireOcrSecret(String)」的上游调用点包括 「InternalEvidenceController.applyParseResult」。
    // 下游影响：「InternalEvidenceController.requireOcrSecret(String)」向下依次触达 「MessageDigest.isEqual」、「properties.ocr」、「properties.ocr().serviceSecret」。
    // 系统意义：「InternalEvidenceController.requireOcrSecret(String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private void requireOcrSecret(String supplied) {
        byte[] expected =
                properties.ocr().serviceSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ForbiddenException("invalid OCR service credential");
        }
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.requireJavaServiceSecret(String)」。
    // 具体功能：「InternalEvidenceController.requireJavaServiceSecret(String)」：强制校验Java服务Secret；实际协作者为 「properties.security」、「requireSecret」、「properties.security().serviceSecret」，最终返回「void」。
    // 上游调用：「InternalEvidenceController.requireJavaServiceSecret(String)」的上游调用点包括 「InternalEvidenceController.content」。
    // 下游影响：「InternalEvidenceController.requireJavaServiceSecret(String)」向下依次触达 「properties.security」、「requireSecret」、「properties.security().serviceSecret」。
    // 系统意义：「InternalEvidenceController.requireJavaServiceSecret(String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private void requireJavaServiceSecret(String supplied) {
        requireSecret(
                properties.security().serviceSecret(),
                supplied,
                "invalid Java service credential");
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.requireSecret(String,String,String)」。
    // 具体功能：「InternalEvidenceController.requireSecret(String,String,String)」：强制校验Secret；实际协作者为 「MessageDigest.isEqual」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「InternalEvidenceController.requireSecret(String,String,String)」的上游调用点包括 「InternalEvidenceController.requireJavaServiceSecret」。
    // 下游影响：「InternalEvidenceController.requireSecret(String,String,String)」向下依次触达 「MessageDigest.isEqual」。
    // 系统意义：「InternalEvidenceController.requireSecret(String,String,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static void requireSecret(String expectedValue, String supplied, String message) {
        byte[] expected = expectedValue.getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ForbiddenException(message);
        }
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「InternalEvidenceController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「InternalEvidenceController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「InternalEvidenceController.correlationId(HttpServletRequest,String)」的上游调用点包括 「InternalEvidenceController.applyParseResult」。
    // 下游影响：「InternalEvidenceController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「InternalEvidenceController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
