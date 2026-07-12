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

@Validated
@RestController
@RequestMapping("/internal/evidence")
public class InternalEvidenceController {

    private final EvidenceParseResultService service;
    private final EvidenceApplicationService evidenceService;
    private final AppProperties properties;
    private final Clock clock;

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

    private void requireOcrSecret(String supplied) {
        byte[] expected =
                properties.ocr().serviceSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ForbiddenException("invalid OCR service credential");
        }
    }

    private void requireJavaServiceSecret(String supplied) {
        requireSecret(
                properties.security().serviceSecret(),
                supplied,
                "invalid Java service credential");
    }

    private static void requireSecret(String expectedValue, String supplied, String message) {
        byte[] expected = expectedValue.getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ForbiddenException(message);
        }
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
