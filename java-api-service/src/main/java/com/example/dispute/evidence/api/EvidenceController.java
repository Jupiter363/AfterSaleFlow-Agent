package com.example.dispute.evidence.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.BuildDossierResult;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/cases/{caseId}")
public class EvidenceController {

    private final EvidenceApplicationService service;
    private final Clock clock;

    public EvidenceController(EvidenceApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping(
            value = "/evidences",
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
                        occurredAt,
                        actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success(result, request));
    }

    @PostMapping("/dossier/build")
    public ApiResponse<BuildDossierResult> build(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.buildDossier(caseId, actor(authentication)), request);
    }

    @GetMapping("/dossier")
    public ApiResponse<BuildDossierResult> get(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(service.getDossier(caseId, actor(authentication)), request);
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
