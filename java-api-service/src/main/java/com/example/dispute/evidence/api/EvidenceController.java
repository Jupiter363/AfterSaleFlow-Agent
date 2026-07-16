/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：把证据能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「upload」、「catalog」、「submitBatch」、「deletePending」、「content」、「verify」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceContentView;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceCompletionStatusView;
import com.example.dispute.evidence.application.EvidenceCompletionView;
import com.example.dispute.evidence.application.EvidenceDossierQueryService;
import com.example.dispute.evidence.application.EvidenceSubmissionService;
import com.example.dispute.evidence.application.EvidenceSubmissionView;
import com.example.dispute.evidence.application.EvidenceVerificationCommand;
import com.example.dispute.evidence.application.EvidenceVerificationService;
import com.example.dispute.evidence.application.EvidenceVerificationView;
import com.example.dispute.evidence.application.EvidenceView;
import com.example.dispute.evidence.application.FrozenEvidenceDossierView;
import com.example.dispute.evidence.application.RoleScopedEvidenceView;
import com.example.dispute.room.application.IntakeProgressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 所属模块：【证据与版本化卷宗 / HTTP 接口层】类型「EvidenceController」。
// 类型职责：把证据能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「EvidenceController」、「upload」、「catalog」、「submitBatch」、「deletePending」、「content」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}")
public class EvidenceController {

    private final EvidenceApplicationService service;
    private final EvidenceCatalogService catalogService;
    private final EvidenceVerificationService verificationService;
    private final EvidenceCompletionService completionService;
    private final EvidenceDossierQueryService dossierQueryService;
    private final EvidenceSubmissionService submissionService;
    private final IntakeProgressService intakeProgressService;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.EvidenceController(EvidenceApplicationService,EvidenceCatalogService,EvidenceVerificationService,EvidenceCompletionService,EvidenceDossierQueryService,EvidenceSubmissionService,Clock)」。
    // 具体功能：「EvidenceController.EvidenceController(EvidenceApplicationService,EvidenceCatalogService,EvidenceVerificationService,EvidenceCompletionService,EvidenceDossierQueryService,EvidenceSubmissionService,Clock)」：通过构造器接收 「service」(EvidenceApplicationService)、「catalogService」(EvidenceCatalogService)、「verificationService」(EvidenceVerificationService)、「completionService」(EvidenceCompletionService)、「dossierQueryService」(EvidenceDossierQueryService)、「submissionService」(EvidenceSubmissionService)、「clock」(Clock) 并保存为「EvidenceController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceController.EvidenceController(EvidenceApplicationService,EvidenceCatalogService,EvidenceVerificationService,EvidenceCompletionService,EvidenceDossierQueryService,EvidenceSubmissionService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「EvidenceController.EvidenceController(EvidenceApplicationService,EvidenceCatalogService,EvidenceVerificationService,EvidenceCompletionService,EvidenceDossierQueryService,EvidenceSubmissionService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceController.EvidenceController(EvidenceApplicationService,EvidenceCatalogService,EvidenceVerificationService,EvidenceCompletionService,EvidenceDossierQueryService,EvidenceSubmissionService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceController(
            EvidenceApplicationService service,
            EvidenceCatalogService catalogService,
            EvidenceVerificationService verificationService,
            EvidenceCompletionService completionService,
            EvidenceDossierQueryService dossierQueryService,
            EvidenceSubmissionService submissionService,
            IntakeProgressService intakeProgressService,
            Clock clock) {
        this.service = service;
        this.catalogService = catalogService;
        this.verificationService = verificationService;
        this.completionService = completionService;
        this.dossierQueryService = dossierQueryService;
        this.submissionService = submissionService;
        this.intakeProgressService = intakeProgressService;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,Authentication,HttpServletRequest)」：处理「POST /evidence」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.upload」、「ResponseEntity.status」、「actor」、「success」，并返回「ResponseEntity<ApiResponse<EvidenceView>>」。
    // 上游调用：「EvidenceController.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /evidence」HTTP 请求。
    // 下游影响：「EvidenceController.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,Authentication,HttpServletRequest)」向下依次触达 「service.upload」、「ResponseEntity.status」、「actor」、「success」；计算结果以「ResponseEntity<ApiResponse<EvidenceView>>」交给调用方。
    // 系统意义：「EvidenceController.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping(
            value = "/evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceView>> upload(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("evidence_type") @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}")
                    String evidenceType,
            @RequestParam("source_type") @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}")
                    String sourceType,
            @RequestParam(defaultValue = "PARTIES")
                    @Pattern(regexp = "PRIVATE|PARTIES|PLATFORM")
                    String visibility,
            @RequestParam(name = "model_processing_authorized", defaultValue = "false")
                    boolean modelProcessingAuthorized,
            @RequestParam("claimed_fact") @NotBlank @Size(min = 5, max = 1000)
                    String claimedFact,
            @RequestParam("truth_attested") @AssertTrue
                    boolean truthAttested,
            @RequestParam(name = "occurred_at", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime occurredAt,
            Authentication authentication,
            HttpServletRequest request) {
        EvidenceView result =
                service.upload(
                        caseId,
                        file,
                        evidenceType,
                        sourceType,
                        visibility,
                        modelProcessingAuthorized,
                        claimedFact,
                        truthAttested,
                        occurredAt,
                        evidenceActor(caseId, authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success(result, request));
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.catalog(String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.catalog(String,Authentication,HttpServletRequest)」：处理「GET /evidence」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「catalogService.catalog」、「success」、「actor」，并返回「ApiResponse<RoleScopedEvidenceView>」。
    // 上游调用：「EvidenceController.catalog(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /evidence」HTTP 请求。
    // 下游影响：「EvidenceController.catalog(String,Authentication,HttpServletRequest)」向下依次触达 「catalogService.catalog」、「success」、「actor」；计算结果以「ApiResponse<RoleScopedEvidenceView>」交给调用方。
    // 系统意义：「EvidenceController.catalog(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/evidence")
    public ApiResponse<RoleScopedEvidenceView> catalog(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                catalogService.catalog(caseId, evidenceReadActor(caseId, authentication)), request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.submitBatch(String,EvidenceSubmissionRequest,String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.submitBatch(String,EvidenceSubmissionRequest,String,Authentication,HttpServletRequest)」：处理「POST /evidence/submissions」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「submissionService.submit」、「command.toCommand」、「success」、「actor」，并返回「ApiResponse<EvidenceSubmissionView>」。
    // 上游调用：「EvidenceController.submitBatch(String,EvidenceSubmissionRequest,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /evidence/submissions」HTTP 请求。
    // 下游影响：「EvidenceController.submitBatch(String,EvidenceSubmissionRequest,String,Authentication,HttpServletRequest)」向下依次触达 「submissionService.submit」、「command.toCommand」、「success」、「actor」；计算结果以「ApiResponse<EvidenceSubmissionView>」交给调用方。
    // 系统意义：「EvidenceController.submitBatch(String,EvidenceSubmissionRequest,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/evidence/submissions")
    public ApiResponse<EvidenceSubmissionView> submitBatch(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody EvidenceSubmissionRequest command,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                submissionService.submit(
                        caseId,
                        command.toCommand(),
                        evidenceActor(caseId, authentication),
                        idempotencyKey,
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.deletePending(String,String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.deletePending(String,String,Authentication,HttpServletRequest)」：处理「DELETE /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「submissionService.deletePending」、「actor」、「success」，并返回「ApiResponse<Void>」。
    // 上游调用：「EvidenceController.deletePending(String,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「DELETE /」HTTP 请求。
    // 下游影响：「EvidenceController.deletePending(String,String,Authentication,HttpServletRequest)」向下依次触达 「submissionService.deletePending」、「actor」、「success」；计算结果以「ApiResponse<Void>」交给调用方。
    // 系统意义：「EvidenceController.deletePending(String,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @DeleteMapping("/evidence/{evidenceId}")
    public ApiResponse<Void> deletePending(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            Authentication authentication,
            HttpServletRequest request) {
        submissionService.deletePending(
                caseId, evidenceId, evidenceActor(caseId, authentication));
        return success(null, request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.content(String,String,Authentication)」。
    // 具体功能：「EvidenceController.content(String,String,Authentication)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.content」、「ResponseEntity.ok」、「ContentDisposition.attachment」、「MediaType.parseMediaType」，并返回「ResponseEntity<byte[]>」。
    // 上游调用：「EvidenceController.content(String,String,Authentication)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「EvidenceController.content(String,String,Authentication)」向下依次触达 「service.content」、「ResponseEntity.ok」、「ContentDisposition.attachment」、「MediaType.parseMediaType」；计算结果以「ResponseEntity<byte[]>」交给调用方。
    // 系统意义：「EvidenceController.content(String,String,Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/evidence/{evidenceId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            Authentication authentication) {
        EvidenceContentView content =
                service.content(caseId, evidenceId, evidenceReadActor(caseId, authentication));
        String filename =
                content.filename() == null || content.filename().isBlank()
                        ? evidenceId
                        : content.filename();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(
                        MediaType.parseMediaType(
                                content.contentType() == null
                                                || content.contentType().isBlank()
                                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                        : content.contentType()))
                .body(content.content());
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.verify(String,String,EvidenceVerificationCommand,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.verify(String,String,EvidenceVerificationCommand,Authentication,HttpServletRequest)」：处理「POST /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「success」、「actor」、「correlationId」，并返回「ApiResponse<EvidenceVerificationView>」。
    // 上游调用：「EvidenceController.verify(String,String,EvidenceVerificationCommand,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /」HTTP 请求。
    // 下游影响：「EvidenceController.verify(String,String,EvidenceVerificationCommand,Authentication,HttpServletRequest)」向下依次触达 「success」、「actor」、「correlationId」；计算结果以「ApiResponse<EvidenceVerificationView>」交给调用方。
    // 系统意义：「EvidenceController.verify(String,String,EvidenceVerificationCommand,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/evidence/{evidenceId}/verify")
    public ApiResponse<EvidenceVerificationView> verify(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            @Valid @RequestBody EvidenceVerificationCommand command,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                verificationService.verify(
                        caseId,
                        evidenceId,
                        command,
                        evidenceActor(caseId, authentication),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.complete(String,String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.complete(String,String,Authentication,HttpServletRequest)」：处理「POST /evidence/complete」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「completionService.complete」、「success」、「actor」，并返回「ApiResponse<EvidenceCompletionView>」。
    // 上游调用：「EvidenceController.complete(String,String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /evidence/complete」HTTP 请求。
    // 下游影响：「EvidenceController.complete(String,String,Authentication,HttpServletRequest)」向下依次触达 「completionService.complete」、「success」、「actor」；计算结果以「ApiResponse<EvidenceCompletionView>」交给调用方。
    // 系统意义：「EvidenceController.complete(String,String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/evidence/complete")
    public ApiResponse<EvidenceCompletionView> complete(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                completionService.complete(
                        caseId, evidenceActor(caseId, authentication), idempotencyKey),
                request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.completion(String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.completion(String,Authentication,HttpServletRequest)」：处理「GET /evidence/completion」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「completionService.status」、「success」、「actor」，并返回「ApiResponse<EvidenceCompletionStatusView>」。
    // 上游调用：「EvidenceController.completion(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /evidence/completion」HTTP 请求。
    // 下游影响：「EvidenceController.completion(String,Authentication,HttpServletRequest)」向下依次触达 「completionService.status」、「success」、「actor」；计算结果以「ApiResponse<EvidenceCompletionStatusView>」交给调用方。
    // 系统意义：「EvidenceController.completion(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/evidence/completion")
    public ApiResponse<EvidenceCompletionStatusView> completion(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                completionService.status(
                        caseId, evidenceReadActor(caseId, authentication)), request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.frozenDossier(String,int,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.frozenDossier(String,int,Authentication,HttpServletRequest)」：处理「GET /」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「success」、「actor」，并返回「ApiResponse<FrozenEvidenceDossierView>」。
    // 上游调用：「EvidenceController.frozenDossier(String,int,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /」HTTP 请求。
    // 下游影响：「EvidenceController.frozenDossier(String,int,Authentication,HttpServletRequest)」向下依次触达 「success」、「actor」；计算结果以「ApiResponse<FrozenEvidenceDossierView>」交给调用方。
    // 系统意义：「EvidenceController.frozenDossier(String,int,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/evidence-dossiers/{version}")
    public ApiResponse<FrozenEvidenceDossierView> frozenDossier(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable int version,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                dossierQueryService.get(
                        caseId, version, evidenceReadActor(caseId, authentication)),
                request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.latestDossier(String,Authentication,HttpServletRequest)」。
    // 具体功能：「EvidenceController.latestDossier(String,Authentication,HttpServletRequest)」：处理「GET /evidence-dossiers/latest」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「dossierQueryService.latest」、「success」、「actor」，并返回「ApiResponse<FrozenEvidenceDossierView>」。
    // 上游调用：「EvidenceController.latestDossier(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /evidence-dossiers/latest」HTTP 请求。
    // 下游影响：「EvidenceController.latestDossier(String,Authentication,HttpServletRequest)」向下依次触达 「dossierQueryService.latest」、「success」、「actor」；计算结果以「ApiResponse<FrozenEvidenceDossierView>」交给调用方。
    // 系统意义：「EvidenceController.latestDossier(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/evidence-dossiers/latest")
    public ApiResponse<FrozenEvidenceDossierView> latestDossier(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                dossierQueryService.latest(
                        caseId, evidenceReadActor(caseId, authentication)), request);
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.success(T,HttpServletRequest)」。
    // 具体功能：「EvidenceController.success(T,HttpServletRequest)」：构建成功；实际协作者为 「ApiResponse.success」、「correlationId」，最终返回「ApiResponse<T>」。
    // 上游调用：「EvidenceController.success(T,HttpServletRequest)」的上游调用点包括 「EvidenceController.upload」、「EvidenceController.catalog」、「EvidenceController.submitBatch」、「EvidenceController.deletePending」。
    // 下游影响：「EvidenceController.success(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「correlationId」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「EvidenceController.success(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.actor(Authentication)」。
    // 具体功能：「EvidenceController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「EvidenceController.actor(Authentication)」的上游调用点包括 「EvidenceController.upload」、「EvidenceController.catalog」、「EvidenceController.submitBatch」、「EvidenceController.deletePending」。
    // 下游影响：「EvidenceController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「EvidenceController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private AuthenticatedActor evidenceActor(
            String caseId, Authentication authentication) {
        AuthenticatedActor actor = actor(authentication);
        intakeProgressService.assertEvidenceAccess(caseId, actor);
        return actor;
    }

    private AuthenticatedActor evidenceReadActor(
            String caseId, Authentication authentication) {
        AuthenticatedActor actor = actor(authentication);
        intakeProgressService.assertEvidenceReadAccess(caseId, actor);
        return actor;
    }

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「EvidenceController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceController.correlationId(HttpServletRequest,String)」的上游调用点包括 「EvidenceController.submitBatch」、「EvidenceController.verify」、「EvidenceController.success」。
    // 下游影响：「EvidenceController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
