package com.example.dispute.evidence.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/evidences")
public class InternalEvidenceController {

    private final EvidenceParseResultService service;
    private final AppProperties properties;
    private final Clock clock;

    public InternalEvidenceController(
            EvidenceParseResultService service, AppProperties properties, Clock clock) {
        this.service = service;
        this.properties = properties;
        this.clock = clock;
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

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
