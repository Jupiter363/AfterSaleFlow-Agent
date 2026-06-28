package com.example.dispute.evaluation.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.ClosureView;
import com.example.dispute.evaluation.application.EvaluationMetricsView;
import com.example.dispute.evaluation.application.EvaluationReportView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ClosureController {

    private final CaseClosureService service;
    private final Clock clock;

    public ClosureController(CaseClosureService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/cases/{caseId}/close")
    public ApiResponse<ClosureView> close(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String traceId = correlation(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId =
                correlation(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.close(
                        caseId,
                        idempotencyKey,
                        actor(authentication),
                        traceId,
                        requestId),
                requestId,
                traceId,
                Instant.now(clock));
    }

    @GetMapping("/cases/{caseId}/evaluation")
    public ApiResponse<EvaluationReportView> evaluation(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.evaluation(caseId, actor(authentication)), request);
    }

    @GetMapping("/evaluations/metrics")
    public ApiResponse<EvaluationMetricsView> metrics(
            Authentication authentication, HttpServletRequest request) {
        return success(service.metrics(actor(authentication)), request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlation(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlation(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlation(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException(
                "correlation id filter did not run");
    }
}
