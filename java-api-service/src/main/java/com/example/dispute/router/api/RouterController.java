package com.example.dispute.router.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.router.application.RouteDecisionView;
import com.example.dispute.router.application.RouterApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/cases/{caseId}/route")
public class RouterController {

    private final RouterApplicationService service;
    private final Clock clock;

    public RouterController(RouterApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ApiResponse<RouteDecisionView> route(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}")
                    String caseId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.route(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal(),
                        idempotencyKey),
                requestId,
                traceId,
                Instant.now(clock));
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
