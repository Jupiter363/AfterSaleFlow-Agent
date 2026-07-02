package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
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

@Validated
@RestController
@RequestMapping("/internal/disputes")
public class InternalDisputeImportController {

    private final DisputeImportService service;
    private final Clock clock;

    public InternalDisputeImportController(DisputeImportService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<ImportedDisputeView>> importDispute(
            @Valid @RequestBody ImportDisputeRequest request,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        ImportedDisputeView imported =
                service.importDispute(
                        request.toCommand(),
                        (AuthenticatedActor) authentication.getPrincipal(),
                        idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                imported,
                                requestId,
                                traceId,
                                Instant.now(clock)));
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
