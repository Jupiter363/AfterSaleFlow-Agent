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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
    private final Clock clock;

    public EvidenceController(
            EvidenceApplicationService service,
            EvidenceCatalogService catalogService,
            EvidenceVerificationService verificationService,
            EvidenceCompletionService completionService,
            EvidenceDossierQueryService dossierQueryService,
            EvidenceSubmissionService submissionService,
            Clock clock) {
        this.service = service;
        this.catalogService = catalogService;
        this.verificationService = verificationService;
        this.completionService = completionService;
        this.dossierQueryService = dossierQueryService;
        this.submissionService = submissionService;
        this.clock = clock;
    }

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
            @RequestParam(required = false)
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
                        occurredAt,
                        actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success(result, request));
    }

    @GetMapping("/evidence")
    public ApiResponse<RoleScopedEvidenceView> catalog(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                catalogService.catalog(caseId, actor(authentication)), request);
    }

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
                        actor(authentication),
                        idempotencyKey,
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    @DeleteMapping("/evidence/{evidenceId}")
    public ApiResponse<Void> deletePending(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            Authentication authentication,
            HttpServletRequest request) {
        submissionService.deletePending(caseId, evidenceId, actor(authentication));
        return success(null, request);
    }

    @GetMapping("/evidence/{evidenceId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable
                    @Pattern(regexp = "EVIDENCE_[A-Za-z0-9_-]{1,119}")
                    String evidenceId,
            Authentication authentication) {
        EvidenceContentView content =
                service.content(caseId, evidenceId, actor(authentication));
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
                        actor(authentication),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    @PostMapping("/evidence/complete")
    public ApiResponse<EvidenceCompletionView> complete(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                completionService.complete(
                        caseId, actor(authentication), idempotencyKey),
                request);
    }

    @GetMapping("/evidence/completion")
    public ApiResponse<EvidenceCompletionStatusView> completion(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                completionService.status(caseId, actor(authentication)), request);
    }

    @GetMapping("/evidence-dossiers/{version}")
    public ApiResponse<FrozenEvidenceDossierView> frozenDossier(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable int version,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                dossierQueryService.get(caseId, version, actor(authentication)),
                request);
    }

    @GetMapping("/evidence-dossiers/latest")
    public ApiResponse<FrozenEvidenceDossierView> latestDossier(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                dossierQueryService.latest(caseId, actor(authentication)), request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
