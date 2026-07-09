package com.example.dispute.outcome.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
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
@RequestMapping("/api/disputes/{caseId}/outcome")
public class CaseOutcomeController {

    private final CaseOutcomeService service;
    private final Clock clock;

    public CaseOutcomeController(CaseOutcomeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<CaseOutcomeView> get(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.get(caseId, actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    @PostMapping("/review/confirm")
    public ApiResponse<ReviewDecisionView> confirmDraft(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody OutcomeReviewDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.confirmDraft(
                        caseId,
                        body.reason(),
                        idempotencyKey,
                        actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    @PostMapping("/review/modify")
    public ApiResponse<ReviewDecisionView> modifyDraft(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody OutcomeReviewDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.modifyDraft(
                        caseId,
                        body.reason(),
                        body.approvedPlan(),
                        idempotencyKey,
                        actor(authentication)),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }

    public record OutcomeReviewDecisionRequest(
            @NotBlank @Size(max = 2000) String reason,
            JsonNode approvedPlan) {}
}
