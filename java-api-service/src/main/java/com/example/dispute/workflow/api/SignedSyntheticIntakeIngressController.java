package com.example.dispute.workflow.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeIngressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/internal/disputes/{caseId}/intake/signed-synthetic/commands")
@ConditionalOnBean(SignedSyntheticIntakeIngressService.class)
public class SignedSyntheticIntakeIngressController {

    private final SignedSyntheticIntakeIngressService service;
    private final Clock clock;

    public SignedSyntheticIntakeIngressController(
            SignedSyntheticIntakeIngressService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CaseCommandAcceptance>> accept(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestHeader("X-Tenant-Surrogate")
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    String tenantSurrogate,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    String commandId,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @Valid @RequestBody SignedSyntheticIntakeIngressRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        CaseCommandAcceptance accepted = service.accept(
                request.toAttempt(),
                request.toInertCommand(tenantSurrogate, caseId, commandId),
                request.expectedProcessRevision(),
                request.payloadSizeBytes(),
                actor(authentication),
                traceId,
                requestId,
                traceparent);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(accepted, requestId, traceId, clock.instant()));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        return value == null ? "" : value.toString();
    }
}
